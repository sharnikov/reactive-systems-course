package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]

  context.system.scheduler.schedule(0.millis, 100.millis, new Runnable {
    override def run(): Unit = {
      pending.foreach { pendingOne =>
        acks.get(pendingOne.seq).foreach { case (_, replicate) =>
          val snapshot = Snapshot(replicate.key, replicate.valueOption, pendingOne.seq)
          replica ! snapshot
        }
      }
    }
  })
  
  var _seqCounter = 0L
  def nextSeq() = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  def receive: Receive = {

    case Replicate(key, valueOption, id) =>
      val nextS = nextSeq()
      acks = acks + (nextS -> (sender() -> Replicate(key, valueOption, id)))
      val snapshot = Snapshot(key, valueOption, nextS)
      pending = pending :+ snapshot
      replica ! snapshot

    case SnapshotAck(key, seq) =>
      acks.get(seq).foreach { case (client, replicate) =>
        acks = acks - seq
        pending = pending.filter(_.seq == seq)
        client ! Replicated(key, replicate.id)
      }
  }

}
