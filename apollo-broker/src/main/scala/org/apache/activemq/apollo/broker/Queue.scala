/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker

import java.util.concurrent.TimeUnit

import org.fusesource.hawtdispatch._
import protocol.ProtocolFactory
import collection.mutable.ListBuffer
import org.apache.activemq.apollo.broker.store._
import org.apache.activemq.apollo.util._
import org.apache.activemq.apollo.util.list._
import org.fusesource.hawtdispatch.{ListEventAggregator, DispatchQueue, BaseRetained}
import OptionSupport._
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import org.fusesource.hawtbuf.Buffer
import java.lang.UnsupportedOperationException
import org.apache.activemq.apollo.dto._
import security.SecuredResource._
import security.{SecuredResource, SecurityContext}

object Queue extends Log {
  val subcsription_counter = new AtomicInteger(0)

  val PREFTCH_LOAD_FLAG = 1.toByte
  val PREFTCH_HOLD_FLAG = 2.toByte

  class MemorySpace {
    var items = 0
    var size = 0
    var size_max = 0

    def +=(delivery:Delivery) = {
      items += 1
      size += delivery.size
    }

    def -=(delivery:Delivery) = {
      items -= 1
      size -= delivery.size
    }
  }

}

import Queue._

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class Queue(val router: LocalRouter, val store_id:Long, var binding:Binding, var config:QueueDTO) extends BaseRetained with BindableDeliveryProducer with DeliveryConsumer with BaseService with DomainDestination with Dispatched with SecuredResource {
  def id = binding.id

  override def toString = binding.destination.toString

  def virtual_host = router.virtual_host

  val resource_kind = binding match {
    case x:DurableSubscriptionQueueBinding=> DurableSubKind
    case x:QueueDomainQueueBinding=> QueueKind
    case _ => OtherKind
  }

  var producers = ListBuffer[BindableDeliveryProducer]()
  var inbound_sessions = Set[DeliverySession]()
  var all_subscriptions = Map[DeliveryConsumer, Subscription]()
  var exclusive_subscriptions = ListBuffer[Subscription]()

  def filter = binding.message_filter

  override val dispatch_queue: DispatchQueue = createQueue(id);

  def destination_dto: DestinationDTO = binding.binding_dto

  debug("created queue: " + id)

  override def dispose: Unit = {
    ack_source.cancel
  }

  val ack_source = createSource(new ListEventAggregator[(Subscription#AcquiredQueueEntry, DeliveryResult, StoreUOW)](), dispatch_queue)
  ack_source.setEventHandler(^ {drain_acks});
  ack_source.resume

  val session_manager = new SessionSinkMux[Delivery](messages, dispatch_queue, Delivery) {
    override def time_stamp = now
  }

  // sequence numbers.. used to track what's in the store.
  var message_seq_counter = 1L

  val entries = new LinkedNodeList[QueueEntry]()
  val head_entry = new QueueEntry(this, 0L).head
  var tail_entry = new QueueEntry(this, next_message_seq)
  entries.addFirst(head_entry)

  //
  // In-frequently accessed tuning configuration.
  //

  //
  // Frequently accessed tuning configuration.
  //

  /**
   * Should this queue persistently store it's entries?
   */
  var tune_persistent = true

  /**
   * Should messages be swapped out of memory if
   * no consumers need the message?
   */
  var tune_swap = true

  /**
   * The number max number of swapped queue entries to load
   * for the store at a time.  Note that swapped entries are just
   * reference pointers to the actual messages.  When not loaded,
   * the batch is referenced as sequence range to conserve memory.
   */
  var tune_swap_range_size = 0

  /**
   *  The amount of memory buffer space to use per subscription.
   */
  var tune_consumer_buffer = 0

  /**
   *  The max memory to allow this queue to grow to.
   */
  var tune_quota = -1L
  
  /**
   *  The message delivery rate (in bytes/sec) at which
   *  the queue enables a enqueue rate throttle
   *  to allow consumers to catchup with producers.
   */
  var tune_catchup_delivery_rate = 0
  
  /**
   *  The rate at which to throttle producers when
   *  consumers are catching up.  
   */
  var tune_catchup_enqueue_rate = 0

  /**
   *  Tthe rate at which producers are throttled at.
   */
  var tune_max_enqueue_rate = 0

  def configure(c:QueueDTO) = {
    config = c
    tune_persistent = virtual_host.store !=null && config.persistent.getOrElse(true)
    tune_swap = tune_persistent && config.swap.getOrElse(true)
    tune_swap_range_size = config.swap_range_size.getOrElse(10000)
    tune_consumer_buffer = Option(config.consumer_buffer).map(MemoryPropertyEditor.parse(_).toInt).getOrElse(256*1024)
    tune_catchup_delivery_rate = Option(config.catchup_delivery_rate).map(MemoryPropertyEditor.parse(_).toInt).getOrElse(-1)
    tune_catchup_enqueue_rate = Option(config.catchup_enqueue_rate).map(MemoryPropertyEditor.parse(_).toInt).getOrElse(tune_catchup_delivery_rate)
    tune_max_enqueue_rate = Option(config.max_enqueue_rate).map(MemoryPropertyEditor.parse(_).toInt).getOrElse(-1)

    tune_quota = Option(config.quota).map(MemoryPropertyEditor.parse(_)).getOrElse(-1)

    if( tune_persistent ) {
      val record = QueueRecord(store_id, binding.binding_kind, binding.binding_data)
      virtual_host.store.add_queue(record) { rc => Unit }
    }

    auto_delete_after = config.auto_delete_after.getOrElse(60*5)
    if( auto_delete_after!= 0 ) {
      // we don't auto delete explicitly configured queues,
      // non destination queues, or unified queues.
      if( config.unified.getOrElse(false) || !binding.isInstanceOf[QueueDomainQueueBinding] || !LocalRouter.is_wildcard_config(config) ) {
        auto_delete_after = 0
      }
    }
  }
  dispatch_queue {
    configure(config)
  }

  var now = System.currentTimeMillis

  var enqueue_item_counter = 0L
  var enqueue_size_counter = 0L
  var enqueue_ts = now;

  var dequeue_item_counter = 0L
  var dequeue_size_counter = 0L
  var dequeue_ts = now;

  var nack_item_counter = 0L
  var nack_size_counter = 0L
  var nack_ts = now;

  var expired_item_counter = 0L
  var expired_size_counter = 0L
  var expired_ts = now;

  def queue_size = enqueue_size_counter - dequeue_size_counter
  def queue_items = enqueue_item_counter - dequeue_item_counter

  var swapping_in_size = 0
  var swapping_out_size = 0

  val producer_swapped_in = new MemorySpace
  val consumer_swapped_in = new MemorySpace

  var swap_out_item_counter = 0L
  var swap_out_size_counter = 0L

  var swap_in_item_counter = 0L
  var swap_in_size_counter = 0L

  var producer_counter = 0L
  var consumer_counter = 0L
  var tail_prefetch = 0L

  var individual_swapped_items = 0

  val swap_source = createSource(EventAggregators.INTEGER_ADD, dispatch_queue)
  swap_source.setEventHandler(^{ swap_messages });
  swap_source.resume

  var restored_from_store = false

  var auto_delete_after = 0
  var idled_at = 0L

  def swapped_in_items = this.producer_swapped_in.items + this.consumer_swapped_in.items
  def swapped_in_size = this.producer_swapped_in.size + this.consumer_swapped_in.size
  def swapped_in_size_max = this.producer_swapped_in.size_max + this.consumer_swapped_in.size_max

  def get_queue_metrics:DestMetricsDTO = {
    dispatch_queue.assertExecuting()
    val rc = new DestMetricsDTO

    rc.enqueue_item_counter = this.enqueue_item_counter
    rc.enqueue_size_counter = this.enqueue_size_counter
    rc.enqueue_ts = this.enqueue_ts

    rc.dequeue_item_counter = this.dequeue_item_counter
    rc.dequeue_size_counter = this.dequeue_size_counter
    rc.dequeue_ts = this.dequeue_ts

    rc.nack_item_counter = this.nack_item_counter
    rc.nack_size_counter = this.nack_size_counter
    rc.nack_ts = this.nack_ts

    rc.expired_item_counter = this.expired_item_counter
    rc.expired_size_counter = this.expired_size_counter
    rc.expired_ts = this.expired_ts

    rc.queue_size = this.queue_size
    rc.queue_items = this.queue_items

    rc.swap_out_item_counter = this.swap_out_item_counter
    rc.swap_out_size_counter = this.swap_out_size_counter
    rc.swap_in_item_counter = this.swap_in_item_counter
    rc.swap_in_size_counter = this.swap_in_size_counter

    rc.swapping_in_size = this.swapping_in_size
    rc.swapping_out_size = this.swapping_out_size

    rc.swapped_in_items = swapped_in_items
    rc.swapped_in_size = swapped_in_size
    rc.swapped_in_size_max = swapped_in_size_max

    rc.producer_counter = this.producer_counter
    rc.consumer_counter = this.consumer_counter

    rc.producer_count = this.producers.size
    rc.consumer_count = this.all_subscriptions.size
    rc
  }

  def status(entries:Boolean=false) = {
    val rc = new QueueStatusDTO
    rc.id = this.id
    rc.state = this.service_state.toString
    rc.state_since = this.service_state.since
    rc.binding = this.binding.binding_dto
    rc.config = this.config
    rc.metrics = this.get_queue_metrics
    rc.metrics.current_time = now

    if( entries ) {
      var cur = this.head_entry
      while( cur!=null ) {

        val e = new EntryStatusDTO
        e.seq = cur.seq
        e.count = cur.count
        e.size = cur.size
        e.consumer_count = cur.parked.size
        e.is_prefetched = cur.is_prefetched
        e.state = cur.label

        rc.entries.add(e)

        cur = if( cur == this.tail_entry ) {
          null
        } else {
          cur.nextOrTail
        }
      }
    }

    this.inbound_sessions.foreach { session:DeliverySession =>
      val link = new LinkDTO()
      session.producer.connection match {
        case Some(connection) =>
          link.kind = "connection"
          link.id = connection.id.toString
          link.label = connection.transport.getRemoteAddress.toString
        case _ =>
          link.kind = "unknown"
          link.label = "unknown"
      }
      link.enqueue_item_counter = session.enqueue_item_counter
      link.enqueue_size_counter = session.enqueue_size_counter
      link.enqueue_ts = session.enqueue_ts
      rc.producers.add(link)
    }

    this.all_subscriptions.valuesIterator.toSeq.foreach{ sub =>
      val link = new QueueConsumerLinkDTO
      sub.consumer.connection match {
        case Some(connection) =>
          link.kind = "connection"
          link.id = connection.id.toString
          link.label = connection.transport.getRemoteAddress.toString
        case _ =>
          link.kind = "unknown"
          link.label = "unknown"
      }
      link.position = sub.pos.seq
      link.enqueue_item_counter = sub.session.enqueue_item_counter
      link.enqueue_size_counter = sub.session.enqueue_size_counter
      link.enqueue_ts = sub.session.enqueue_ts
      link.total_ack_count = sub.total_ack_count
      link.total_nack_count = sub.total_nack_count
      link.acquired_size = sub.acquired_size
      link.acquired_count = sub.acquired_count
      link.waiting_on = if( sub.full ) {
        "ack"
      } else if( sub.pos.is_tail ) {
        "producer"
      } else if( !sub.pos.is_loaded ) {
        "load"
      } else {
        "dispatch"
      }
      rc.consumers.add(link)
    }
    rc
  }

  def update(on_completed:Runnable) = dispatch_queue {

    val prev_persistent = tune_persistent
    val prev_consumer_size = tune_consumer_buffer

    configure(binding.config(virtual_host))

    val consumer_buffer_change = tune_consumer_buffer-prev_consumer_size
    if( consumer_buffer_change!=0 ) {
      // for each
      all_subscriptions.values.foreach { sub =>
        // open session
        if( sub.session!=null ) {
          // change the queue capacity, by the change in consumer buffer change.
          change_consumer_capacity(consumer_buffer_change)
        }
      }
    }

    restore_from_store {
      check_idle
      trigger_swap
      on_completed.run
    }
  }

  def check_idle {
    if (producers.isEmpty && all_subscriptions.isEmpty && queue_items==0 ) {
      if (idled_at==0) {
        idled_at = now
        if( auto_delete_after!=0 ) {
          dispatch_queue.after(auto_delete_after, TimeUnit.SECONDS) {
            if( now == idled_at ) {
              router._destroy_queue(this)
            }
          }
        }
      }
    } else {
      idled_at = 0
    }
  }

  def restore_from_store(on_completed: => Unit) {
    if (!restored_from_store && tune_persistent) {
      restored_from_store = true
      virtual_host.store.list_queue_entry_ranges(store_id, tune_swap_range_size) { ranges =>
        dispatch_queue {
          if (ranges != null && !ranges.isEmpty) {

            ranges.foreach {
              range =>
                val entry = new QueueEntry(Queue.this, range.first_entry_seq).init(range)
                entries.addLast(entry)

                message_seq_counter = range.last_entry_seq + 1
                enqueue_item_counter += range.count
                enqueue_size_counter += range.size
                tail_entry = new QueueEntry(Queue.this, next_message_seq)
            }

            all_subscriptions.valuesIterator.foreach( _.rewind(head_entry) )
            debug("restored: " + enqueue_item_counter)
          }
          on_completed
        }
      }
    } else {
      on_completed
    }
  }

  protected def _start(on_completed: Runnable) = {
    restore_from_store {


      // by the time this is run, consumers and producers may have already joined.
      on_completed.run
      schedule_periodic_maintenance
      // wake up the producers to fill us up...
      if (messages.refiller != null) {
        messages.refiller.run
      }

      // kick off dispatching to the consumers.
      check_idle
      trigger_swap
      dispatch_queue << head_entry

    }
  }

  protected def _stop(on_completed: Runnable) = {
    // Disconnect the producers..
    producers.foreach { producer =>
      disconnect(producer)
    }
    // Close all the subscriptions..
    all_subscriptions.values.toArray.foreach { sub:Subscription =>
      sub.close()
    }

    trigger_swap

    destination_dto match {
      case d:DurableSubscriptionDestinationDTO =>
        DestinationMetricsSupport.add_destination_metrics(virtual_host.dead_dsub_metrics, get_queue_metrics)
      case t:TopicDestinationDTO =>
        // metrics are taken care of by topic
      case _ =>
        DestinationMetricsSupport.add_destination_metrics(virtual_host.dead_queue_metrics, get_queue_metrics)
    }
    on_completed.run
  }

  def might_unfill[T](func: =>T):T = {
    val was_full = messages.full
    try {
      func
    } finally {
      if( was_full && !messages.full ) {
        messages.refiller.run
      }
    }
  }

  def change_producer_capacity(amount:Int) = might_unfill {
    producer_swapped_in.size_max += amount
  }
  def change_consumer_capacity(amount:Int) = might_unfill {
    consumer_swapped_in.size_max += amount
  }

  object messages extends Sink[Delivery] {

    var refiller: Runnable = null

    def full = (producer_swapped_in.size >= producer_swapped_in.size_max) || is_enqueue_throttled || !service_state.is_started || (tune_quota >=0 && queue_size > tune_quota)

    def offer(delivery: Delivery): Boolean = {
      if (full) {
        false
      } else {

        // Don't even enqueue if the message has expired.
        val expiration = delivery.message.expiration
        if( expiration != 0 && expiration <= now ) {
          expired(delivery)
          return true
        }

        val entry = tail_entry
        tail_entry = new QueueEntry(Queue.this, next_message_seq)
        val queueDelivery = delivery.copy
        queueDelivery.seq = entry.seq
        entry.init(queueDelivery)
        
        if( tune_persistent ) {
          queueDelivery.uow = delivery.uow
        }

        entries.addLast(entry)
        enqueue_item_counter += 1
        enqueue_size_counter += entry.size
        enqueue_ts = now;

        // To decrease the enqueue throttle.
        enqueue_remaining_take(entry.size)

        // Do we need to do a persistent enqueue???
        if (queueDelivery.uow != null) {
          entry.as_loaded.store
        }

        if( entry.hasSubs ) {
          // try to dispatch it directly...
          entry.dispatch
        }

        if( entry.as_loaded.acquired) {
          // Enqueued message aquired.
        } else if( tail_prefetch > 0 ) {
          // Enqueued message prefeteched.
          tail_prefetch -= entry.size
          entry.prefetch_flags = PREFTCH_LOAD_FLAG
          entry.load(consumer_swapped_in)
        } else {
//          val prev = entry.getPrevious
//          if( (prev.as_loaded!=null && prev.as_loaded.swapping_out) || (prev.as_swapped!=null && !prev.as_swapped.swapping_in) ) {
//            // Swap it out ASAP
//            entry.swap(true)
//            println("Enqueued message swapped.")
//          } else {
//            trigger_swap
//            // Avoid swapping right away..
//          }
          entry.swap(true)
        }

        // release the store batch...
        if (queueDelivery.uow != null) {
          queueDelivery.uow.release
          queueDelivery.uow = null
        }

        true
      }
    }
  }

  def expired(delivery:Delivery):Unit = {
    expired_ts = now
    expired_item_counter += 1
    expired_size_counter += delivery.size
  }

  def expired(entry:QueueEntry, dequeue:Boolean=true):Unit = {
    if(dequeue) {
      might_unfill {
        dequeue_item_counter += 1
        dequeue_size_counter += entry.size
        dequeue_ts = now
      }
    }

    expired_ts = now
    expired_item_counter += 1
    expired_size_counter += entry.size
  }

  def display_stats: Unit = {
    info("contains: %d messages worth %,.2f MB of data, producers are %s, %d/%d buffer space used.", queue_items, (queue_size.toFloat / (1024 * 1024)), {if (messages.full) "being throttled" else "not being throttled"}, swapped_in_size, swapped_in_size_max)
    info("total messages enqueued %d, dequeues %d ", enqueue_item_counter, dequeue_item_counter)
  }

  def display_active_entries: Unit = {
    var cur = entries.getHead
    var total_items = 0L
    var total_size = 0L
    while (cur != null) {
      if (cur.is_loaded || cur.hasSubs || cur.is_prefetched || cur.is_swapped_range ) {
        info("  => " + cur)
      }

      total_size += cur.size
      if (cur.is_swapped || cur.is_loaded) {
        total_items += 1
      } else if (cur.is_swapped_range ) {
        total_items += cur.as_swapped_range.count
      }
      
      cur = cur.getNext
    }
    info("tail: " + tail_entry)

    // sanitiy checks..
    if(total_items != queue_items) {
      warn("queue_items mismatch, found %d, expected %d", total_size, queue_items)
    }
    if(total_size != queue_size) {
      warn("queue_size mismatch, found %d, expected %d", total_size, queue_size)

    }
  }

  def trigger_swap = {
    if( tune_swap ) {
      swap_source.merge(1)
    }
  }

  def swap_messages:Unit = {
    dispatch_queue.assertExecuting()

    if( !service_state.is_started )
      return

    var cur = entries.getHead
    while( cur!=null ) {

      // reset the prefetch flags and handle expiration...
      cur.prefetch_flags = 0
      val next = cur.getNext

      // handle expiration...
      if( cur.expiration != 0 && cur.expiration <= now ) {
        cur.state match {
          case x:QueueEntry#SwappedRange =>
            // load the range to expire the messages in it.
            cur.load(null)
          case x:QueueEntry#Swapped =>
            // remove the expired swapped message.
            expired(cur)
            x.remove
          case x:QueueEntry#Loaded =>
            // remove the expired message if it has not been
            // acquired.
            if( !x.acquired ) {
              expired(cur)
              x.remove
            }
          case _ =>
        }
      }
      cur = next
    }

    // Set the prefetch flags
    all_subscriptions.valuesIterator.foreach{ x=>
      x.refill_prefetch
    }

    // swap out messages.
    cur = entries.getHead
    while( cur!=null ) {
      val next = cur.getNext
      val loaded = cur.as_loaded
      if( loaded!=null ) {
        if( cur.prefetch_flags==0 && !loaded.acquired  ) {
          val asap = !cur.as_loaded.acquired
          cur.swap(asap)
        } else {
          cur.load(consumer_swapped_in)
        }
      }
      cur = next
    }                               


    // Combine swapped items into swapped ranges
    if( individual_swapped_items > tune_swap_range_size*2 ) {

      debug("Looking for swapped entries to combine")

      var distance_from_sub = tune_swap_range_size;
      var cur = entries.getHead
      var combine_counter = 0;

      while( cur!=null ) {

        // get the next now.. since cur may get combined and unlinked
        // from the entry list.
        val next = cur.getNext

        if( cur.prefetch_flags!=0 ) {
          distance_from_sub = 0
        } else {
          distance_from_sub += 1
          if( cur.can_combine_with_prev ) {
            cur.getPrevious.as_swapped_range.combineNext
            combine_counter += 1
          } else {
            if( cur.is_swapped && distance_from_sub > tune_swap_range_size ) {
              cur.swapped_range
              combine_counter += 1
            }
          }

        }
        cur = next
      }
      debug("combined %d entries", combine_counter)
    }
    
    if(!messages.full) {
      messages.refiller.run
    }

  }

  var delivery_rate = 0L
  def swapped_out_size = queue_size - (producer_swapped_in.size + consumer_swapped_in.size)

  def schedule_periodic_maintenance:Unit = dispatch_queue.after(1, TimeUnit.SECONDS) {
    if( service_state.is_started ) {
      var elapsed = System.currentTimeMillis-now
      now += elapsed

      delivery_rate = 0L

      var consumer_stall_ms = 0L
      var load_stall_ms = 0L

      all_subscriptions.values.foreach{ sub=>
        val (cs, ls) = sub.adjust_prefetch_size
        consumer_stall_ms += cs
        load_stall_ms += ls
        if(!sub.browser) {
          delivery_rate += sub.enqueue_size_per_interval
        }
      }
      
      val rate_adjustment = elapsed.toFloat / 1000.toFloat
      delivery_rate  = (delivery_rate / rate_adjustment).toLong

      val stall_ratio = ((consumer_stall_ms*100)+1).toFloat / ((load_stall_ms*100)+1).toFloat

      // Figure out what the max enqueue rate should be.
      max_enqueue_rate = Int.MaxValue
      if( tune_catchup_delivery_rate>=0 && tune_catchup_enqueue_rate>=0 && delivery_rate>tune_catchup_delivery_rate && swapped_out_size > 0 && stall_ratio < 1.0 ) {
        max_enqueue_rate = tune_catchup_enqueue_rate
      }
      if(tune_max_enqueue_rate >=0 ) {
        max_enqueue_rate = max_enqueue_rate.min(tune_max_enqueue_rate)
      }
      if( max_enqueue_rate < Int.MaxValue ) {
        if(enqueues_remaining==null) {
          enqueues_remaining = new LongCounter()
          enqueue_throttle_release(enqueues_remaining)
        }
      } else {
        if(enqueues_remaining!=null) {
          enqueues_remaining = null
        }
      }

      swap_messages
      schedule_periodic_maintenance
    }
  }
    
  var max_enqueue_rate = Int.MaxValue
  var enqueues_remaining:LongCounter = _
  
  def is_enqueue_throttled = enqueues_remaining!=null && enqueues_remaining.get() <= 0

  def enqueue_remaining_take(amount:Int) = {
    if(enqueues_remaining!=null) {
      enqueues_remaining.addAndGet(-amount)
    }
  }
  
  def enqueue_throttle_release(throttle:LongCounter):Unit = {
    if( enqueues_remaining==throttle ) {
      might_unfill {
        val amount = max_enqueue_rate / 10
        val remaining = throttle.get
//        if(remaining < 0) {
//          throttle.addAndGet(amount)
//        } else {
          throttle.set(amount)
//        }
      }
      dispatch_queue.after(100, TimeUnit.MILLISECONDS) {
        enqueue_throttle_release(throttle)
      }
    }
  }

  def drain_acks = might_unfill {
    ack_source.getData.foreach {
      case (entry, consumed, uow) =>
        consumed match {
          case Consumed =>
            entry.ack(uow)
          case Expired=>
            entry.entry.queue.expired(entry.entry, false)
            entry.ack(uow)
          case Delivered =>
            entry.entry.redelivered
            entry.nack
          case Poisoned    =>
            entry.entry.redelivered
            entry.nack
          case Undelivered =>
            entry.nack
        }
        if( uow!=null ) {
          uow.release()
        }
    }
  }

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the DeliveryConsumer trait.  Allows this queue
  // to receive messages from producers.
  //
  /////////////////////////////////////////////////////////////////////

  def matches(delivery: Delivery) = filter.matches(delivery.message)

  def is_persistent = tune_persistent

  class QueueDeliverySession(val producer: DeliveryProducer) extends DeliverySession with SessionSinkFilter[Delivery]{
    retain

    override def toString = Queue.this.toString
    override def consumer = Queue.this

    val session_max = producer.send_buffer_size
    val downstream = session_manager.open(producer.dispatch_queue, session_max)

    dispatch_queue {
      inbound_sessions += this
      change_producer_capacity( session_max )
    }

    def close = {
      session_manager.close(downstream)
      dispatch_queue {
        change_producer_capacity( -session_max )
        inbound_sessions -= this
      }
      release
    }

    def offer(delivery: Delivery) = {
      if (downstream.full) {
        false
      } else {
        delivery.message.retain
        if( tune_persistent && delivery.uow!=null ) {
          delivery.uow.retain
        }
        val rc = downstream.offer(delivery)
        assert(rc, "session should accept since it was not full")
        true
      }
    }
  }
  def connect(p: DeliveryProducer) = new QueueDeliverySession(p)

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the Route trait.  Allows consumers to bind/unbind
  // from this queue so that it can send messages to them.
  //
  /////////////////////////////////////////////////////////////////////

  def connected() = {}

  def bind(value: DeliveryConsumer, ctx:SecurityContext): Result[Zilch, String] = {
    if( ctx!=null ) {
      if( value.browser ) {
        if( !virtual_host.authorizer.can(ctx, "receive", this) ) {
          return new Failure("Not authorized to browse the queue")
        }
      } else {
        if( !virtual_host.authorizer.can(ctx, "consume", this) ) {
          return new Failure("Not authorized to consume from the queue")
        }
      }
    }
    bind(value::Nil)
    Success(Zilch)
  }

  def bind(values: List[DeliveryConsumer]) = {
    values.foreach(_.retain)
    dispatch_queue {
      for (consumer <- values) {
        val sub = new Subscription(this, consumer)
        sub.open
        consumer.release()
      }
    }
  }

  def unbind(values: List[DeliveryConsumer]):Unit = dispatch_queue {
    for (consumer <- values) {
      all_subscriptions.get(consumer) match {
        case Some(subscription) =>
          subscription.close
        case None =>
      }
    }
  }

  def disconnected() = throw new RuntimeException("unsupported")

  def bind(destination:DestinationDTO, consumer: DeliveryConsumer) = {
    bind(consumer::Nil)
  }
  def unbind(consumer: DeliveryConsumer, persistent:Boolean):Unit = {
    unbind(consumer::Nil)
  }

  def connect (destination:DestinationDTO, producer:BindableDeliveryProducer) = {
    import OptionSupport._
    if( config.unified.getOrElse(false) ) {
      // this is a unified queue.. actually have the produce bind to the topic, instead of the
      val topic = router.topic_domain.get_or_create_destination(binding.destination, binding.binding_dto, null).success
      topic.connect(destination, producer)
    } else {
      dispatch_queue {
        producers += producer
        producer_counter += 1
        check_idle
      }
      producer.bind(this::Nil)
    }
  }

  def disconnect (producer:BindableDeliveryProducer) = {
    import OptionSupport._
    if( config.unified.getOrElse(false) ) {
      val topic = router.topic_domain.get_or_create_destination(binding.destination, binding.binding_dto, null).success
      topic.disconnect(producer)
    } else {
      dispatch_queue {
        producers -= producer
        check_idle
      }
      producer.unbind(this::Nil)
    }
  }

  override def connection:Option[BrokerConnection] = None

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation methods.
  //
  /////////////////////////////////////////////////////////////////////


  private def next_message_seq = {
    val rc = message_seq_counter
    message_seq_counter += 1
    rc
  }

  val swap_out_completes_source = createSource(new ListEventAggregator[QueueEntry#Loaded](), dispatch_queue)
  swap_out_completes_source.setEventHandler(^ {drain_swap_out_completes});
  swap_out_completes_source.resume

  def drain_swap_out_completes() = might_unfill {
    val data = swap_out_completes_source.getData
    data.foreach { loaded =>
      loaded.swapped_out
    }
  }

  val store_load_source = createSource(new ListEventAggregator[(QueueEntry#Swapped, MessageRecord)](), dispatch_queue)
  store_load_source.setEventHandler(^ {drain_store_loads});
  store_load_source.resume


  def drain_store_loads() = {
    val data = store_load_source.getData
    data.foreach { case (swapped,message_record) =>
      swapped.swapped_in(message_record)
    }

    data.foreach { case (swapped,_) =>
      if( swapped.entry.hasSubs ) {
        swapped.entry.run
      }
    }
  }

}

object QueueEntry extends Sizer[QueueEntry] with Log {
  def size(value: QueueEntry): Int = value.size
}

class QueueEntry(val queue:Queue, val seq:Long) extends LinkedNode[QueueEntry] with Comparable[QueueEntry] with Runnable {
  import QueueEntry._

  // Subscriptions waiting to dispatch this entry.
  var parked:List[Subscription] = Nil

  // subscriptions will set this to non-zero if they are interested
  // in the entry.
  var prefetch_flags:Byte = 0

  // The current state of the entry: Head | Tail | Loaded | Swapped | SwappedRange
  var state:EntryState = new Tail

  def is_prefetched = prefetch_flags == 1

  def <(value:QueueEntry) = this.seq < value.seq
  def <=(value:QueueEntry) = this.seq <= value.seq

  def head():QueueEntry = {
    state = new Head
    this
  }

  def tail():QueueEntry = {
    state = new Tail
    this
  }

  def init(delivery:Delivery):QueueEntry = {
    queue.producer_swapped_in += delivery
    state = new Loaded(delivery, false, queue.producer_swapped_in)
    this
  }

  def init(qer:QueueEntryRecord):QueueEntry = {
    val locator = new AtomicReference[Array[Byte]](Option(qer.message_locator).map(_.toByteArray).getOrElse(null))
    state = new Swapped(qer.message_key, locator, qer.size, qer.expiration, qer.redeliveries)
    this
  }

  def init(range:QueueEntryRange):QueueEntry = {
    state = new SwappedRange(range.last_entry_seq, range.count, range.size, range.expiration)
    this
  }

  def hasSubs = !parked.isEmpty

  /**
   * Dispatches this entry to the consumers and continues dispatching subsequent
   * entries as long as the dispatch results in advancing in their dispatch position.
   */
  def run() = {
    queue.assert_executing
    var cur = this;
    while( cur!=null && cur.isLinked ) {
      val next = cur.getNext
      cur = if( cur.dispatch ) {
        next
      } else {
        null
      }
    }
  }

  def ::=(sub:Subscription) = {
    parked ::= sub
  }

  def :::=(l:List[Subscription]) = {
    parked :::= l
  }


  def -=(s:Subscription) = {
    parked = parked.filterNot(_ == s)
  }

  def nextOrTail():QueueEntry = {
    var entry = getNext
    if (entry == null) {
      entry = queue.tail_entry
    }
    entry
  }


  def compareTo(o: QueueEntry) = {
    (seq - o.seq).toInt
  }

  def toQueueEntryRecord = {
    val qer = new QueueEntryRecord
    qer.queue_key = queue.store_id
    qer.entry_seq = seq
    qer.message_key = state.message_key
    qer.message_locator = Option(state.message_locator).flatMap(x=> Option(x.get)).map(new Buffer(_)).getOrElse(null)
    qer.size = state.size
    qer.expiration = expiration
    qer
  }

  override def toString = {
    "{seq: "+seq+", prefetch_flags: "+prefetch_flags+", value: "+state+", subscriptions: "+parked+"}"
  }

  /////////////////////////////////////////////////////
  //
  // State delegates..
  //
  /////////////////////////////////////////////////////

  // What state is it in?
  def as_head = state.as_head
  def as_tail = state.as_tail

  def as_swapped = state.as_swapped
  def as_swapped_range = state.as_swapped_range
  def as_loaded = state.as_loaded

  def label = state.label

  def is_tail = as_tail!=null
  def is_head = as_head!=null
  def is_loaded = as_loaded!=null
  def is_swapped = as_swapped!=null
  def is_swapped_range = as_swapped_range!=null
  def is_swapped_or_swapped_range = is_swapped || is_swapped_range

  // These should not change the current state.
  def count = state.count
  def size = state.size
  def expiration = state.expiration
  def redeliveries = state.redeliveries
  def redelivered = state.redelivered
  def messageKey = state.message_key
  def is_swapped_or_swapping_out = state.is_swapped_or_swapping_out
  def dispatch() = state.dispatch

  // These methods may cause a change in the current state.
  def swap(asap:Boolean) = state.swap_out(asap)
  def load(space:MemorySpace) = state.swap_in(space)
  def remove = state.remove

  def swapped_range = state.swap_range

  def can_combine_with_prev = {
    getPrevious !=null &&
      getPrevious.is_swapped_range &&
        ( is_swapped || is_swapped_range ) &&
          (getPrevious.count + count  < queue.tune_swap_range_size)
  }

  trait EntryState {

    final def entry:QueueEntry = QueueEntry.this

    def as_tail:Tail = null
    def as_loaded:Loaded = null
    def as_swapped:Swapped = null
    def as_swapped_range:SwappedRange = null
    def as_head:Head = null

    /**
     * Gets the size of this entry in bytes.  The head and tail entries always return 0.
     */
    def size = 0

    /**
     * When the entry expires or 0 if it does not expire.
     */
    def expiration = 0L

    /**
     * When the entry expires or 0 if it does not expire.
     */
    def redeliveries:Short = throw new UnsupportedOperationException()

    /**
     * Called to increment the redelivery counter
     */
    def redelivered:Unit = {}

    /**
     * Gets number of messages that this entry represents
     */
    def count = 0

    /**
     * Retuns a string label used to describe this state.
     */
    def label:String

    /**
     * Gets the message key for the entry.
     * @returns -1 if it is not known.
     */
    def message_key = -1L

    def message_locator: AtomicReference[Array[Byte]] = null

    /**
     * Attempts to dispatch the current entry to the subscriptions position at the entry.
     * @returns true if at least one subscription advanced to the next entry as a result of dispatching.
     */
    def dispatch() = false

    /**
     * @returns true if the entry is either swapped or swapping.
     */
    def is_swapped_or_swapping_out = false

    /**
     * Triggers the entry to get swapped in if it's not already swapped in.
     */
    def swap_in(space:MemorySpace) = {}

    /**
     * Triggers the entry to get swapped out if it's not already swapped.
     */
    def swap_out(asap:Boolean) = {}

    def swap_range:Unit = throw new AssertionError("should only be called on swapped entries");

    /**
     * Removes the entry from the queue's linked list of entries.  This gets called
     * as a result of an aquired ack.
     */
    def remove:Unit = {
      // advance subscriptions that were on this entry..
      advance(parked)
      parked = Nil

      // take the entry of the entries list..
      unlink
      //TODO: perhaps refill subscriptions.
    }

    /**
     * Advances the specified subscriptions to the next entry in
     * the linked list
     */
    def advance(advancing: Seq[Subscription]): Unit = {
      val nextPos = nextOrTail
      nextPos :::= advancing.toList
      advancing.foreach(_.advance(nextPos))
      queue.trigger_swap
    }

  }

  /**
   *  Used for the head entry.  This is the starting point for all new subscriptions.
   */
  class Head extends EntryState {

    def label = "head"
    override  def toString = "head"
    override def as_head = this

    /**
     * New subs get parked here at the Head.  There is nothing to actually dispatch
     * in this entry.. just advance the parked subs onto the next entry.
     */
    override def dispatch() = {
      if( parked != Nil ) {
        advance(parked)
        parked = Nil
        true

      } else {
        false
      }
    }

    override def remove = throw new AssertionError("Head entry cannot be removed")
    override def swap_in(space:MemorySpace) = throw new AssertionError("Head entry cannot be loaded")
    override def swap_out(asap:Boolean) = throw new AssertionError("Head entry cannot be swapped")
  }

  /**
   * This state is used on the last entry of the queue.  It still has not been initialized
   * with a message, but it may be holding subscriptions.  This state transitions to Loaded
   * once a message is received.
   */
  class Tail extends EntryState {

    def label = "tail"
    override  def toString = "tail"
    override def as_tail:Tail = this

    override def remove = throw new AssertionError("Tail entry cannot be removed")
    override def swap_in(space:MemorySpace) = throw new AssertionError("Tail entry cannot be loaded")
    override def swap_out(asap:Boolean) = throw new AssertionError("Tail entry cannot be swapped")

  }

  /**
   * The entry is in this state while a message is loaded in memory.  A message must be in this state
   * before it can be dispatched to a subscription.
   */
  class Loaded(val delivery: Delivery, var stored:Boolean, var space:MemorySpace) extends EntryState {

    assert( delivery!=null, "delivery cannot be null")

    var acquired = false
    var swapping_out = false
    var storing = false

    def label = {
      var rc = "loaded"
      if( acquired ) {
        rc += "|aquired"
      }
      if( swapping_out ) {
        rc += "|swapping out"
      }
      rc
    }

    override def toString = { "loaded:{ stored: "+stored+", swapping_out: "+swapping_out+", acquired: "+acquired+", size:"+size+"}" }

    override def count = 1
    override def size = delivery.size
    override def expiration = delivery.message.expiration
    override def message_key = delivery.storeKey
    override def message_locator = delivery.storeLocator
    override def redeliveries = delivery.redeliveries

    override def redelivered = delivery.redeliveries = ((delivery.redeliveries+1).min(Short.MaxValue)).toShort

    var remove_pending = false

    override def is_swapped_or_swapping_out = {
      swapping_out
    }

    override  def as_loaded = this

    def store = {
      if(!storing) {
        storing = true
        delivery.uow.enqueue(toQueueEntryRecord)
        delivery.uow.on_flush {
          queue.swap_out_completes_source.merge(this)
        }
      }
    }

    override def swap_out(asap:Boolean) = {
      if( queue.tune_swap && !swapping_out ) {
        swapping_out=true

        queue.swapping_out_size+=size
        if( stored ) {
          swapped_out
        } else {

          // The storeBatch is only set when called from the messages.offer method
          if( delivery.uow!=null ) {
            if( asap ) {
              delivery.uow.complete_asap
            }
          } else {

            // Are we swapping out a non-persistent message?
            if( !storing ) {
              assert( delivery.storeKey == -1 )

              delivery.uow = queue.virtual_host.store.create_uow
              val uow = delivery.uow
              delivery.storeLocator = new AtomicReference[Array[Byte]]()
              delivery.storeKey = uow.store(delivery.createMessageRecord )
              store
              if( asap ) {
                uow.complete_asap
              }
              uow.release
              delivery.uow = null

            } else {
              if( asap ) {
                queue.virtual_host.store.flush_message(message_key) {
                }
              }
            }
          }
        }
      }
    }

    def swapped_out() = {
      assert( state == this )
      storing = false
      stored = true
      delivery.uow = null
      if( swapping_out ) {
        swapping_out = false
        space -= delivery
        queue.swapping_out_size-=size

        queue.swap_out_size_counter += size
        queue.swap_out_item_counter += 1

        state = new Swapped(delivery.storeKey, delivery.storeLocator, size, expiration, redeliveries)
        if( can_combine_with_prev ) {
          getPrevious.as_swapped_range.combineNext
        }
        if( remove_pending ) {
          state.remove
        }
      } else {
        if( remove_pending ) {
          delivery.message.release
          space -= delivery
          super.remove
        }
      }
    }

    override def swap_in(space:MemorySpace) = {
      if(space ne this.space) {
        this.space -= delivery
        this.space = space
        this.space += delivery
      }
      if( swapping_out ) {
        swapping_out = false
        queue.swapping_out_size-=size
      }
    }

    override def remove = {
      if( storing | remove_pending ) {
        remove_pending = true
      } else {
        delivery.message.release
        space -= delivery
        super.remove
      }
    }

    override def dispatch():Boolean = {

      queue.assert_executing

      if( !acquired && expiration != 0 && expiration <= queue.now ) {
        queue.expired(entry)
        remove
        return true
      }

      // Nothing to dispatch if we don't have subs..
      if( parked.isEmpty ) {
        return false
      }

      var heldBack = ListBuffer[Subscription]()
      var advancing = ListBuffer[Subscription]()

      var acquiringSub: Subscription = null
      parked.foreach{ sub=>

        if( sub.browser ) {
          if (!sub.matches(delivery)) {
            // advance: not interested.
            advancing += sub
          } else {
            if (sub.offer(delivery)) {
              // advance: accepted...
              advancing += sub
            } else {
              // hold back: flow controlled
              heldBack += sub
            }
          }

        } else {
          if( acquired ) {
            // advance: another sub already acquired this entry..
            advancing += sub
          } else {
            if (!sub.matches(delivery)) {
              // advance: not interested.
              advancing += sub
            } else {

              // Find the the first exclusive target of the message
              val exclusive_target = queue.exclusive_subscriptions.find( _.matches(delivery) )

              // Is the current sub not the exclusive target?
              if( exclusive_target.isDefined && (exclusive_target.get != sub) ) {
                // advance: not interested.
                advancing += sub
              } else {
                // Is the sub flow controlled?
                if( sub.full ) {
                  // hold back: flow controlled
                  heldBack += sub
                } else {
                  // advance: accepted...
                  acquiringSub = sub
                  acquired = true

                  val acquiredQueueEntry = sub.acquire(entry)
                  val acquiredDelivery = delivery.copy
                  acquiredDelivery.ack = (consumed, uow)=> {
                    if( uow!=null ) {
                      uow.retain()
                    }
                    queue.ack_source.merge((acquiredQueueEntry, consumed, uow))
                  }

                  val accepted = sub.offer(acquiredDelivery)
                  assert(accepted, "sub should have accepted, it had reported not full earlier.")
                }
              }
            }
          }
        }
      }

      // The acquiring sub is added last to the list so that
      // the other competing subs get first dibs at the next entry.
      if( acquiringSub != null ) {
        advancing += acquiringSub
      }

      if ( advancing.isEmpty ) {
        return false
      } else {

        // The held back subs stay on this entry..
        parked = heldBack.toList

        // the advancing subs move on to the next entry...
        advance(advancing)

//        // swap this entry out if it's not going to be needed soon.
//        if( !hasSubs && prefetch_flags==0 ) {
//          // then swap out to make space...
//          var asap = !acquired
//          flush(asap)
//        }
        queue.trigger_swap
        return true
      }
    }
  }

  /**
   * Loaded entries are moved into the Swapped state reduce memory usage.  Once a Loaded
   * entry is persisted, it can move into this state.  This state only holds onto the
   * the massage key so that it can reload the message from the store quickly when needed.
   */
  class Swapped(override val message_key:Long, override val message_locator:AtomicReference[Array[Byte]], override val size:Int, override val expiration:Long, var _redeliveries:Short) extends EntryState {

    queue.individual_swapped_items += 1

    var swap_in_space:MemorySpace = _

    override def redeliveries = _redeliveries
    override def redelivered = _redeliveries = ((_redeliveries+1).min(Short.MaxValue)).toShort

    override def count = 1

    override def as_swapped = this

    override def is_swapped_or_swapping_out = true

    def label = {
      var rc = "swapped"
      if( swap_in_space!=null ) {
        rc += "|swapping in"
      }
      rc
    }
    override def toString = { "swapped:{ swapping_in: "+swap_in_space+", size:"+size+"}" }

    override def swap_in(space:MemorySpace) = {
      if( swap_in_space==null ) {
//        trace("Start entry load of message seq: %s", seq)
        // start swapping in...
        swap_in_space = space
        queue.swapping_in_size += size
        queue.virtual_host.store.load_message(message_key, message_locator) { delivery =>
          // pass off to a source so it can aggregate multiple
          // loads to reduce cross thread synchronization
          if( delivery.isDefined ) {
            queue.store_load_source.merge((this, delivery.get))
          } else {

            info("Detected store dropped message at seq: %d", seq)

            // Looks like someone else removed the message from the store.. lets just
            // tombstone this entry now.
            queue.dispatch_queue {
              remove
            }
          }
        }
      }
    }

    def swapped_in(messageRecord:MessageRecord) = {
      if( swap_in_space!=null ) {
//        debug("Loaded message seq: ", seq )
        queue.swapping_in_size -= size

        val delivery = new Delivery()
        delivery.message = ProtocolFactory.get(messageRecord.protocol.toString).get.decode(messageRecord)
        delivery.size = messageRecord.size
        delivery.storeKey = messageRecord.key
        delivery.storeLocator = messageRecord.locator
        delivery.redeliveries = redeliveries

        swap_in_space += delivery

        queue.swap_in_size_counter += size
        queue.swap_in_item_counter += 1

        queue.individual_swapped_items -= 1
        state = new Loaded(delivery, true, swap_in_space)
        swap_in_space = null
      } else {
//        debug("Ignoring store load of: ", messageKey)
      }
    }


    override def remove = {
      if( swap_in_space!=null ) {
        swap_in_space = null
        queue.swapping_in_size -= size
      }
      queue.individual_swapped_items -= 1
      super.remove
    }

    override def swap_range = {
      if( swap_in_space!=null ) {
        swap_in_space = null
        queue.swapping_in_size -= size
      }
      queue.individual_swapped_items -= 1
      state = new SwappedRange(seq, 1, size, expiration)
    }
  }

  /**
   * A SwappedRange state is assigned entry is used to represent a rage of swapped entries.
   *
   * Even entries that are Swapped can us a significant amount of memory if the queue is holding
   * thousands of them.  Multiple entries in the swapped state can be combined into a single entry in
   * the SwappedRange state thereby conserving even more memory.  A SwappedRange entry only tracks
   * the first, and last sequnce ids of the range.  When the entry needs to be loaded from the range
   * it replaces the swapped range entry with all the swapped entries by querying the store of all the
   * message keys for the entries in the range.
   */
  class SwappedRange(
    /** the last seq id in the range */
    var last:Long,
    /** the number of items in the range */
    var _count:Int,
    /** size in bytes of the range */
    var _size:Int,
    var _expiration:Long) extends EntryState {


    override def count = _count
    override def size = _size
    override def expiration = _expiration

    var swapping_in = false

    override def as_swapped_range = this

    override def is_swapped_or_swapping_out = true

    def label = {
      var rc = "swapped_range"
      if( swapping_in ) {
        rc = "swapped_range|swapping in"
      }
      rc
    }
    override def toString = { "swapped_range:{ swapping_in: "+swapping_in+", count: "+count+", size: "+size+"}" }

    override def swap_in(space:MemorySpace) = {
      if( !swapping_in ) {
        swapping_in = true
        queue.virtual_host.store.list_queue_entries(queue.store_id, seq, last) { records =>
          if( !records.isEmpty ) {
            queue.dispatch_queue {

              var item_count=0
              var size_count=0

              val tmpList = new LinkedNodeList[QueueEntry]()
              records.foreach { record =>
                val entry = new QueueEntry(queue, record.entry_seq).init(record)
                tmpList.addLast(entry)
                item_count += 1
                size_count += record.size
              }

              // we may need to adjust the enqueue count if entries
              // were dropped at the store level
              var item_delta = (count - item_count)
              val size_delta: Int = size - size_count

              if ( item_delta!=0 || size_delta!=0 ) {
                info("Detected store change in range %d to %d. %d message(s) and %d bytes", seq, last, item_delta, size_delta)
                queue.enqueue_item_counter += item_delta
                queue.enqueue_size_counter += size_delta
              }

              linkAfter(tmpList)
              val next = getNext

              // move the subs to the first entry that we just loaded.
              parked.foreach(_.advance(next))
              next :::= parked
              queue.trigger_swap

              unlink

              // TODO: refill prefetches
            }
          } else {
            warn("range load failed")
          }
        }
      }
    }

    /**
     * Combines this queue entry with the next queue entry.
     */
    def combineNext():Unit = {
      val value = getNext
      assert(value!=null)
      assert(value.is_swapped || value.is_swapped_range)
      if( value.is_swapped ) {
        assert(last < value.seq )
        last = value.seq
        _count += 1
      } else if( value.is_swapped_range ) {
        assert(last < value.seq )
        last = value.as_swapped_range.last
        _count += value.as_swapped_range.count
      }
      if(_expiration == 0){
        _expiration = value.expiration
      } else {
        if( value.expiration != 0 ) {
          _expiration = value.expiration.min(_expiration)
        }
      }
      _size += value.size
      value.remove
    }

  }

}

object Subscription extends Log

/**
 * Interfaces a DispatchConsumer with a Queue.  Tracks current position of the consumer
 * on the queue, and the delivery rate so that slow consumers can be detected.  It also
 * tracks the entries which the consumer has acquired.
 *
 */
class Subscription(val queue:Queue, val consumer:DeliveryConsumer) extends DeliveryProducer with Dispatched {
  import Subscription._

  def dispatch_queue = queue.dispatch_queue

  val id = Queue.subcsription_counter.incrementAndGet
  var acquired = new LinkedNodeList[AcquiredQueueEntry]
  var session: DeliverySession = null
  var pos:QueueEntry = null

  var acquired_size = 0L
  def acquired_count = acquired.size()

  var enqueue_size_per_interval = 0L
  var enqueue_size_at_last_interval = 0L

  var consumer_stall_ms = 0L
  var load_stall_ms = 0L

  var consumer_stall_start = 0L
  var load_stall_start = 0L

  var total_ack_count = 0L
  var total_nack_count = 0L

  override def toString = {
    def seq(entry:QueueEntry) = if(entry==null) null else entry.seq
    "{ id: "+id+", acquired_size: "+acquired_size+", pos: "+seq(pos)+"}"
  }

  def browser = session.consumer.browser
  def exclusive = session.consumer.exclusive

  // This opens up the consumer
  def open() = {
    consumer.retain
    if(consumer.start_from_tail) {
      pos = queue.tail_entry;
    } else {
      pos = queue.head_entry;
    }
    assert(pos!=null)
    consumer.set_starting_seq(pos.seq)

    session = consumer.connect(this)
    session.refiller = dispatch_queue.runnable {
      if(session!=null) {
        check_consumer_stall
      }
      if( pos!=null ) {
        pos.run
      }
    }
    pos ::= this

    queue.all_subscriptions += consumer -> this
    queue.consumer_counter += 1
    queue.change_consumer_capacity( queue.tune_consumer_buffer )

    if( exclusive ) {
      queue.exclusive_subscriptions.append(this)
    }

    if( queue.service_state.is_started ) {
      // kick off the initial dispatch.
      refill_prefetch
      queue.dispatch_queue << pos
    }
    queue.check_idle
  }

  def close() = {
    if(pos!=null) {
      pos -= this
      pos = null

      queue.exclusive_subscriptions = queue.exclusive_subscriptions.filterNot( _ == this )
      queue.all_subscriptions -= consumer
      queue.change_consumer_capacity( - queue.tune_consumer_buffer )


      // nack all the acquired entries.
      var next = acquired.getHead
      while( next !=null ) {
        val cur = next;
        next = next.getNext
        cur.entry.redelivered
        cur.nack // this unlinks the entry.
      }

      if( exclusive ) {
        // rewind all the subs to the start of the queue.
        queue.all_subscriptions.values.foreach(_.rewind(queue.head_entry))
      }

      session.refiller = NOOP
      session.close
      session = null
      consumer.release

      queue.check_idle
      queue.tail_prefetch = 0
      queue.trigger_swap
    } else {}
  }

  /**
   * Advances the subscriptions position to the specified
   * queue entry.
   */
  def advance(value:QueueEntry):Unit = {
    assert(value!=null)
    pos = value
    check_load_stall
    if( tail_parked ) {
        if(session.consumer.close_on_drain) {
          close
        } else {
          var remaining = queue.tune_consumer_buffer - acquired_size;
          queue.tail_prefetch = queue.tail_prefetch.max(remaining)
        }
    }
  }

  /**
   * Rewinds to a previously seen location.. Happens when
   * a nack occurs from another consumer.
   */
  def rewind(value:QueueEntry):Unit = {
    assert(value!=null)
    pos -= this
    value ::= this
    pos = value
    check_load_stall
    queue.dispatch_queue << value // queue up the entry to get dispatched..
  }

  def tail_parked = pos eq queue.tail_entry

  def matches(entry:Delivery) = session.consumer.matches(entry)
  def full = session.full

  def offer(delivery:Delivery) = try {
    session.offer(delivery)
  } finally {
    check_consumer_stall
  }

  def acquire(entry:QueueEntry) = new AcquiredQueueEntry(entry)

  def check_load_stall = {
    if ( pos.is_swapped_or_swapped_range ) {
      if(load_stall_start==0) {
        load_stall_start = queue.virtual_host.broker.now
      }
    } else {
      if(load_stall_start!=0) {
        load_stall_ms += queue.virtual_host.broker.now - load_stall_start
        load_stall_start = 0
      }
    }
  }

  def check_consumer_stall = {
    if ( full ) {
      if(consumer_stall_start==0) {
        consumer_stall_start = queue.virtual_host.broker.now
      }
    } else {
      if( consumer_stall_start!=0 ) {
        consumer_stall_ms += queue.virtual_host.broker.now - consumer_stall_start
        consumer_stall_start = 0
      }
    }
  }

  def adjust_prefetch_size = {

    enqueue_size_per_interval = session.enqueue_size_counter - enqueue_size_at_last_interval
    enqueue_size_at_last_interval = session.enqueue_size_counter

    if(consumer_stall_start !=0) {
      val now = queue.virtual_host.broker.now
      consumer_stall_ms += now - consumer_stall_start
      consumer_stall_start = now
    }

    if(load_stall_start !=0) {
      val now = queue.virtual_host.broker.now
      load_stall_ms += now - load_stall_start
      load_stall_start = now
    }

    val rc = (consumer_stall_ms, load_stall_ms)
    consumer_stall_ms = 0
    load_stall_ms = 0
    rc
  }

  def refill_prefetch = {

    var cursor = if( pos.is_tail ) {
      null // can't prefetch the tail..
    } else if( pos.is_head ) {
      pos.getNext // can't prefetch the head.
    } else {
      pos // start prefetching from the current position.
    }

    var remaining = queue.tune_consumer_buffer - acquired_size; // 3/4 of the prefetch is triggers loading
    while( remaining>0 && cursor!=null ) {
      val next = cursor.getNext
      if( (cursor.prefetch_flags & PREFTCH_LOAD_FLAG) == 0 ) {
        remaining -= cursor.size
        cursor.prefetch_flags = (cursor.prefetch_flags | PREFTCH_LOAD_FLAG).toByte
        cursor.load(queue.consumer_swapped_in)
      }
      cursor = next
    }
    
    // If we hit the tail.. credit it so that we avoid swapping too soon.
    if( cursor == null ) {
      queue.tail_prefetch = queue.tail_prefetch.max(((enqueue_size_per_interval/2) - remaining).max(remaining))
    }


  }

  class AcquiredQueueEntry(val entry:QueueEntry) extends LinkedNode[AcquiredQueueEntry] {

    acquired.addLast(this)
    acquired_size += entry.size

    def ack(uow:StoreUOW):Unit = {
      assert_executing
      if(!isLinked) {
        debug("Internal protocol error: message delivery acked/nacked multiple times: "+entry.seq)
        return
      }
      // The session may have already been closed..
      if( session == null ) {
        return;
      }
      total_ack_count += 1
      if (entry.messageKey != -1) {
        val storeBatch = if( uow == null ) {
          queue.virtual_host.store.create_uow
        } else {
          uow
        }
        storeBatch.dequeue(entry.toQueueEntryRecord)
        if( uow == null ) {
          storeBatch.release
        }
      }
      queue.dequeue_item_counter += 1
      queue.dequeue_size_counter += entry.size
      queue.dequeue_ts = queue.now

      // removes this entry from the acquired list.
      unlink()

      // we may now be able to prefetch some messages..
      acquired_size -= entry.size

      val next = entry.nextOrTail
      entry.remove // entry size changes to 0

      queue.trigger_swap
      next.run
    }

    def nack:Unit = {
      assert_executing
      if(!isLinked) {
        warn("Internal protocol error: message delivery acked/nacked multiple times: "+entry.seq)
        return
      }
      // The session may have already been closed..
      if( session == null ) {
        return;
      }

      total_nack_count += 1
      entry.as_loaded.acquired = false
      acquired_size -= entry.size

      // track for stats
      queue.nack_item_counter += 1
      queue.nack_size_counter += entry.size
      queue.nack_ts = queue.now

      // The following does not need to get done for exclusive subs because
      // they end up rewinding all the sub of the head of the queue.
      if( !exclusive ) {

        // rewind all the matching competing subs past the entry.. back to the entry
        queue.all_subscriptions.valuesIterator.foreach{ sub=>
          if( !sub.browser && entry.seq < sub.pos.seq && sub.matches(entry.as_loaded.delivery)) {
            sub.rewind(entry)
          }
        }

      }
      unlink()
    }
  }

}

