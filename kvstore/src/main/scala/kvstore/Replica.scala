package kvstore

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorRef, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import kvstore.Arbiter._
import akka.pattern.{ask, pipe}

import scala.concurrent.duration._
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  arbiter ! Join

  val second = 1000
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var allReplicas = Set.empty[ActorRef]

  var pendingUpdates = Map.empty[Long, (Persist, ActorRef)]
  val persistance = context.actorOf(persistenceProps)
  var version: Long = 0L

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _: PersistenceException => Restart
    }

  context.system.scheduler.schedule(0.millis, 100.millis, new Runnable {
    override def run(): Unit = {
      pendingUpdates.foreach { case (_, info) =>
        persistance ! info._1
      }
    }
  })

  var pendingReplicas = Map.empty[Long, (ActorRef, Long, Replicate, List[ActorRef])]
  var notStoredIds = Map.empty[Long, (ActorRef, Long, Persist)]

  context.system.scheduler.schedule(0.millis, 100.millis, new Runnable {
    override def run(): Unit = {
      val currentTime = System.currentTimeMillis()
      pendingReplicas.foreach { case (id, info) =>
        info match {
          case (client, time, _, _) if currentTime - time >= second =>
            pendingReplicas = pendingReplicas - id
            notStoredIds = notStoredIds - id
            client ! OperationFailed(id)
          case (_, _, replicate, replicas) =>
            replicas.foreach(_ ! replicate)
        }
      }
    }
  })

  context.system.scheduler.schedule(0.millis, 100.millis, new Runnable {
    override def run(): Unit = {
      val currentTime = System.currentTimeMillis()
      notStoredIds.foreach { case (id, (client, time, persist)) =>
        if (currentTime - time >= second) {
          pendingReplicas = pendingReplicas - id
          notStoredIds = notStoredIds - id
          client ! OperationFailed(id)
        } else {
          persistance ! persist
        }
      }
    }
  })

  var newReplicatorsSynch = Map.empty[Replicate, List[ActorRef]]

  context.system.scheduler.schedule(0.millis, 100.millis, new Runnable {
    override def run(): Unit = {
      newReplicatorsSynch.foreach { case (replcate, replicators) =>
        replicators.foreach { replcator =>
          replcator ! replcate
        }
      }
    }
  })

  def receive = {
    case JoinedPrimary   => context.become(leader orElse replica)
    case JoinedSecondary => context.become(replica)
  }

  val leader: Receive = {

    case Insert(key, value, id) =>
      kv = kv + (key -> value)
      val client = sender()
      val time = System.currentTimeMillis()
      val snapshot = Persist(key, Some(value), id)
      notStoredIds = notStoredIds + (id -> ((client, time, snapshot)))
      persistance ! snapshot

      val allReplicators = secondaries.toList.map(_._2)
      if (allReplicators.nonEmpty) {
        val replicate = Replicate(key, Some(value), id)
        pendingReplicas = pendingReplicas + (id -> ((client, time, replicate, allReplicators)))
        allReplicators.foreach(_ ! replicate)
      }

    case Remove(key, id) =>
      kv = kv - key
      val client = sender()
      val time = System.currentTimeMillis()
      val snapshot = Persist(key, None, id)
      notStoredIds = notStoredIds + (id -> ((client, time, snapshot)))
      persistance ! snapshot

      val allReplicators = secondaries.toList.map(_._2)
      if (allReplicators.nonEmpty) {
        val replicate = Replicate(key, None, id)
        pendingReplicas = pendingReplicas + (id -> ((client, time, replicate, allReplicators)))
        allReplicators.foreach(_ ! replicate)
      }

    case Replicas(replicas) =>

      val allReplicasExceptMain = replicas.filter(_ != self)
      val newReplicas =  allReplicasExceptMain -- allReplicas
      val replicasToRemove = allReplicas -- allReplicasExceptMain

      var newReplicators = List.empty[ActorRef]
      newReplicas.foreach { newReplica =>
        val replicatorProps = Replicator.props(newReplica)
        val replicatorActor = context.actorOf(replicatorProps)
        newReplicators = newReplicators :+ replicatorActor
        secondaries = secondaries + (newReplica -> replicatorActor)
      }

      val replicatorsToRemove = replicasToRemove.map(secondaries.apply)

      secondaries = secondaries -- replicasToRemove

      pendingReplicas = pendingReplicas.mapValues {
        case (client, time, replicate, allReplicators) =>
          (client, time, replicate, allReplicators.filterNot(replicatorsToRemove.contains) ++ newReplicators)
      }

      pendingReplicas = pendingReplicas.filter {
        case (id, (client, _, _, allReplicators)) =>
        if (allReplicators.isEmpty && !notStoredIds.exists(_._1 == id)) client ! OperationAck(id)
        allReplicators.nonEmpty
      }

      newReplicatorsSynch = newReplicatorsSynch.mapValues(_.filterNot(replicatorsToRemove.contains)).filter {
        case (_, value) => value.nonEmpty
      }

      replicatorsToRemove.foreach(_ ! PoisonPill)
      replicasToRemove.foreach(_ ! PoisonPill)

      var notStoredKv = List.empty[Replicate]
      kv.toList.zipWithIndex.foreach { case ((key, value), index) =>
        val replicate = Replicate(key, Some(value), index)
        notStoredKv = replicate :: notStoredKv
        newReplicatorsSynch = newReplicatorsSynch + (replicate -> newReplicators)
        newReplicators.foreach(_ ! replicate)
      }

      allReplicas = allReplicasExceptMain

    case Replicated(key, id) =>
      val currentReplicator = sender()

      newReplicatorsSynch.find { case (replicate, _) => replicate.key == key && replicate.id == id} match {
        case Some((replicate, notSynchReplicators)) if notSynchReplicators.size == 1 =>
          newReplicatorsSynch = newReplicatorsSynch - replicate
        case Some((replicate, notSynchReplicators)) =>
          newReplicatorsSynch = newReplicatorsSynch + (replicate -> notSynchReplicators.filter(_ == sender()))

        case None =>
          pendingReplicas.get(id) match {
            case Some((client, _, _, replicators)) if replicators.contains(currentReplicator) && replicators.size == 1  =>
              pendingReplicas = pendingReplicas - id
              if (notStoredIds.get(id).isEmpty) client ! OperationAck(id)
            case Some((client, startTime, replicate, replicators)) if replicators.contains(currentReplicator) =>
              pendingReplicas = pendingReplicas + (id -> ((client, startTime, replicate, replicators.filter(_ != currentReplicator))))
            case _ =>
          }
      }

    case Persisted(_, seq) =>
      val value = notStoredIds.get(seq)
      value.foreach { case (client, _, _) =>
        notStoredIds = notStoredIds - seq
        if (pendingReplicas.get(seq).isEmpty) client ! OperationAck(seq)
      }
  }

  val replica: Receive = {

    case Get(key, id) =>
      sender() !  GetResult(
        key = key,
        valueOption = kv.get(key),
        id = id
      )

    case Snapshot(key, valueOption, seq) =>
      val replicator = sender()

      valueOption match {

        case Some(value) if version == seq =>
          version = version + 1
          kv = kv + (key -> value)
          val persist = Persist(key, valueOption, seq)
          pendingUpdates = pendingUpdates + (seq -> (persist -> replicator))
          persistance ! persist

        case None if version == seq  =>
          version = version + 1
          kv = kv - key
          val persist = Persist(key, valueOption, seq)
          pendingUpdates = pendingUpdates + (seq -> (persist -> replicator))
          persistance ! persist

        case _ if version > seq =>
          replicator ! SnapshotAck(key, seq)

        case _ if version < seq =>
      }

    case Persisted(key, id) =>
      val value = pendingUpdates.get(id)
      value.foreach { case (_, replicator) =>
        pendingUpdates = pendingUpdates - id
        replicator ! SnapshotAck(key, id)
      }
  }

}