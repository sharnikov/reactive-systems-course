/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import java.util.concurrent.atomic.AtomicInteger

import actorbintree.BinaryTreeSet.GC
import akka.actor._

import scala.collection.immutable.Queue
import akka.pattern.{ask, pipe}

import scala.concurrent.{Await, Future}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef =
    context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case GC =>
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
    case message: Insert =>
      root ! message
    case message: Remove =>
      root ! message
    case message: Contains =>
      root ! message
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case message: Operation =>
      pendingQueue = pendingQueue :+ message
    case CopyFinished =>
      root = newRoot
      pendingQueue.foreach(command => newRoot ! command)
      pendingQueue = Queue.empty[Operation]
      context.become(normal)
    case _ =>
  }
}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) =
    Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val currentElement: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case CopyTo(newNode) =>
      if (removed && context.children.isEmpty) {
        context.parent ! CopyFinished
        self ! PoisonPill
      } else {
        if (!removed) newNode ! Insert(self, ids.getAndIncrement(), currentElement)
        subtrees.values.foreach(_ ! CopyTo(newNode))
        context.become(copying(context.children.toSet, removed))
      }
    case CopyFinished => context.parent ! CopyFinished
    case Contains(requester, id, newElem) =>
      newElem match {
        case e if e == currentElement =>
          responseWithContainsResult(requester, id, !removed)
        case e if e < currentElement =>
          parseChildActor(subtrees.get(Left), requester, id, newElem)
        case e if e > currentElement =>
          parseChildActor(subtrees.get(Right), requester, id, newElem)
      }
    case Insert(requester, id, newElem) =>
      newElem match {
        case e if e == currentElement =>
          removed = false
          responseWithFinishedResult(requester, id)
        case e if e < currentElement =>
          insertChildActor(subtrees.get(Left), Left, requester, id, newElem)
        case e if e > currentElement =>
          insertChildActor(subtrees.get(Right), Right, requester, id, newElem)
      }
    case Remove(requester, id, newElem) =>
      newElem match {
        case e if e == currentElement =>
          removed = true
          responseWithFinishedResult(requester, id)
        case e if e < currentElement =>
          removeChildActor(subtrees.get(Left), requester, id, newElem)
        case e if e > currentElement =>
          removeChildActor(subtrees.get(Right), requester, id, newElem)
      }
    case _ =>
  }

  private def responseWithContainsResult(requester: ActorRef,
                                         id: Int,
                                         isPresent: Boolean) =
    requester ! ContainsResult(id, result = isPresent)

  private def parseChildActor(subtree: Option[ActorRef],
                              requester: ActorRef,
                              id: Int,
                              newElem: Int
                             ) = subtree match {
    case Some(rightActor) => rightActor ! Contains(requester, id, newElem)
    case None => responseWithContainsResult(requester, id, isPresent = false)
  }

  private def responseWithFinishedResult(requester: ActorRef,
                                         id: Int) = {
    requester ! OperationFinished(id)
  }

  private def insertChildActor(subtree: Option[ActorRef],
                               whichChild: Position,
                               requester: ActorRef,
                               id: Int,
                               newElem: Int
                              ) = {
    subtree match {
      case Some(child) => child ! Insert(requester, id, newElem)
      case None =>
        val child = context.actorOf(BinaryTreeNode.props(newElem, initiallyRemoved = false))
        subtrees = subtrees ++ Map(whichChild -> child)
        responseWithFinishedResult(requester, id)
    }
  }

  private def removeChildActor(subtree: Option[ActorRef],
                               requester: ActorRef,
                               id: Int,
                               elem: Int
                              ) = {
    subtree match {
      case Some(child) => child ! Remove(requester, id, elem)
      case None => responseWithFinishedResult(requester, id)
    }
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(_) =>
      //      log.debug("completed copy of {} in {}", elem, sender)
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        self ! PoisonPill
      } else {
        context.become(copying(expected, insertConfirmed = true))
      }

    case _: CopyFinished.type =>
      ((expected - sender).isEmpty, insertConfirmed) match {
        case (true, true) =>
          context.parent ! CopyFinished
          self ! PoisonPill
        case (true, false) =>
          context.become(copying(Set.empty, insertConfirmed))
        case (false, _) =>
          context.become(copying(expected - sender, insertConfirmed))
      }
  }

  val ids = new AtomicInteger(0)
}
