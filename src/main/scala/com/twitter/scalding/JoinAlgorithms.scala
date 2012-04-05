/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding

import cascading.tap._
import cascading.scheme._
import cascading.pipe._
import cascading.pipe.assembly._
import cascading.pipe.joiner.{InnerJoin => CInnerJoin, LeftJoin => CLeftJoin}
import cascading.flow._
import cascading.operation._
import cascading.operation.aggregator._
import cascading.operation.filter._
import cascading.tuple._
import cascading.cascade._

import scala.util.Random

/*
 * Keeps all the logic related to RichPipe joins.
 *
 */
trait JoinAlgorithms {
  import RichPipe._

  def pipe : Pipe

  def coGroupBy(f : Fields, j : JoinMode = InnerJoinMode)(builder : CoGroupBuilder => GroupBuilder) : Pipe = {
    builder(new CoGroupBuilder(f, j)).schedule(pipe.getName, pipe)
  }

  /*
   * WARNING! doing a cross product with even a moderate sized pipe can
   * create ENORMOUS output.  The use-case here is attaching a constant (e.g.
   * a number or a dictionary or set) to each row in another pipe.
   * A common use-case comes from a groupAll and reduction to one row,
   * then you want to send the results back out to every element in a pipe
   *
   * This uses joinWithTiny, so tiny pipe is replicated to all Mappers.  If it
   * is large, this will blow up.  Get it: be foolish here and LOSE IT ALL!
   *
   * Use at your own risk.
   */
  def crossWithTiny(tiny : Pipe) = {
    val tinyJoin = tiny.map(() -> '__joinTiny__) { (u:Unit) => 1 }
    pipe.map(() -> '__joinBig__) { (u:Unit) => 1 }
      .joinWithTiny('__joinBig__ -> '__joinTiny__, tinyJoin)
      .discard('__joinBig__, '__joinTiny__)
  }

  // Rename the collisions and return the pipe and the new names, and the fields to discard
  private def renameCollidingFields(p : Pipe, fields : Fields,
    collisions: Set[Comparable[_]]) : (Pipe, Fields, Fields) = {
    // Here is how we rename colliding fields
    def rename(f : Comparable[_]) : String = "__temp_join_" + f.toString

    // convert to list, so we are explicit that ordering is fixed below:
    val renaming = collisions.toList
    val orig = new Fields(renaming : _*)
    val temp = new Fields(renaming.map { rename } : _*)
    // Now construct the new join keys, where we check for a rename
    // otherwise use the original key:
    val newJoinKeys = new Fields( asList(fields)
      .map { fname =>
        // If we renamed, get the rename, else just use the field
        if (collisions(fname)) {
          rename(fname)
        }
        else fname
      } : _*)
    val renamedPipe = p.rename(orig -> temp)
    (renamedPipe, newJoinKeys, temp)
  }

  /**
  * joins the first set of keys in the first pipe to the second set of keys in the second pipe.
  * All keys must be unique UNLESS it is an inner join, then duplicated join keys are allowed, but
  * the second copy is deleted (as cascading does not allow duplicated field names).
  *
  * Avoid going crazy adding more explicit join modes.  Instead do for some other join
  * mode with a larger pipe:
  * .then { pipe => other.
  *           joinWithSmaller(('other1, 'other2)->('this1, 'this2), pipe, new FancyJoin)
  *       }
  */
  def joinWithSmaller(fs :(Fields,Fields), that : Pipe, joiners : (JoinMode, JoinMode) = (InnerJoinMode, InnerJoinMode), reducers : Int = -1) = {
    // If we are not doing an inner join, the join fields must be disjoint:
    val intersection = asSet(fs._1).intersect(asSet(fs._2))
    if (intersection.size == 0) {
      // Common case: no intersection in names: just CoGroup, which duplicates the grouping fields:
      assignName(pipe).coGroupBy(fs._1, joiners._1) {
        _.coGroup(fs._2, that, joiners._2)
          .reducers(reducers)
      }
    }
    else if (joiners._1 == InnerJoinMode && joiners._2 == InnerJoinMode) {
      /*
       * Since it is an inner join, we only output if the key is present an equal in both sides.
       * For this (common) case, it doesn't matter if we drop one of the matching grouping fields.
       * So, we rename the right hand side to temporary names, then discard them after the operation
       */
      val (renamedThat, newJoinFields, temp) = renameCollidingFields(that, fs._2, intersection)
      assignName(pipe).coGroupBy(fs._1, joiners._1) {
        _.coGroup(newJoinFields, renamedThat, joiners._2)
          .reducers(reducers)
      }.discard(temp)
    }
    else {
      throw new IllegalArgumentException("join keys must be disjoint unless you are doing an InnerJoin.  Found: " +
        fs.toString + ", which overlap with: " + intersection.toString)
    }
  }

  def joinWithLarger(fs : (Fields, Fields), that : Pipe, joiners : (JoinMode, JoinMode) = (InnerJoinMode,InnerJoinMode), reducers : Int = -1) = {
    that.joinWithSmaller((fs._2, fs._1), pipe, joiners, reducers)
  }

  def leftJoinWithSmaller(fs :(Fields,Fields), that : Pipe, reducers : Int = -1) = {
    joinWithSmaller(fs, that, (InnerJoinMode, OuterJoinMode), reducers)
  }

  def leftJoinWithLarger(fs :(Fields,Fields), that : Pipe, reducers : Int = -1) = {
    //We swap the order, and turn left into right:
    that.joinWithSmaller((fs._2, fs._1), pipe, (OuterJoinMode, InnerJoinMode), reducers)
  }

  /**
   * This does an assymmetric join, using cascading's "Join".  This only runs through
   * this pipe once, and keeps the right hand side pipe in memory (but is spillable).
   *
   * joins the first set of keys in the first pipe to the second set of keys in the second pipe.
   * All keys must be unique UNLESS it is an inner join, then duplicated join keys are allowed, but
   * the second copy is deleted (as cascading does not allow duplicated field names).
   *
   * WARNING: this does not work with outer joins, or right joins, only inner and
   * left join versions are given.
   */
  def joinWithTiny(fs :(Fields,Fields), that : Pipe) = {
    val intersection = asSet(fs._1).intersect(asSet(fs._2))
    if (intersection.size == 0) {
      new Join(assignName(pipe), fs._1, assignName(that), fs._2, new CInnerJoin)
    }
    else {
      val (renamedThat, newJoinFields, temp) = renameCollidingFields(that, fs._2, intersection)
      (new Join(assignName(pipe), fs._1, assignName(renamedThat), newJoinFields, new CInnerJoin))
        .discard(temp)
    }
  }

  def leftJoinWithTiny(fs :(Fields,Fields), that : Pipe) = {
    //Rename these pipes to avoid cascading name conflicts
    new Join(assignName(pipe), fs._1, assignName(that), fs._2, new CLeftJoin)
  }

  /*
   * Performs a block join, otherwise known as a replicate fragment join (RF join).
   * The input params leftReplication and rightReplication control the replication of the left and right
   * pipes respectively.
   *
   * This is useful in cases where the data has extreme skew. A symptom of this is that we may see a job stuck for
   * a very long time on a small number of reducers.
   *
   * A block join is way to get around this: we add a random integer field and a replica field
   * to every tuple in the left and right pipes. We then join on the original keys and
   * on these new dummy fields. These dummy fields make it less likely that the skewed keys will
   * be hashed to the same reducer.
   *
   * The final data size is right * rightReplication + left * leftReplication
   * but because of the fragmentation, we are guaranteed the same number of hits as the original join.
   *
   * If the right pipe is really small then you are probably better off with a joinWithTiny. If however
   * the right pipe is medium sized, then you are better off with a blockJoinWithSmaller, and a good rule
   * of thumb is to set rightReplication = left.size / right.size and leftReplication = 1
   *
   * Finally, if both pipes are of similar size, e.g. in case of a self join with a high data skew,
   * then it makes sense to set leftReplication and rightReplication to be approximately equal.
   *
   * NOTE: you can only use an InnerJoin or a LeftJoin with a leftReplication of 1
   * (or a RightJoin with a rightReplication of 1) when doing a blockJoin.
   */
  def blockJoinWithSmaller(fs : (Fields, Fields),
      otherPipe : Pipe, rightReplication : Int = 1, leftReplication : Int = 1,
      joiners : (JoinMode, JoinMode) = (InnerJoinMode, InnerJoinMode), reducers : Int = -1) : Pipe = {

    assert(rightReplication > 0, "Must specify a positive number for the right replication in block join")
    assert(leftReplication > 0, "Must specify a positive number for the left replication in block join")
    assertValidJoinMode(joiners, leftReplication, rightReplication)

    // These are the new dummy fields used in the skew join
    val leftFields = new Fields("__LEFT_I__", "__LEFT_J__")
    val rightFields = new Fields("__RIGHT_I__", "__RIGHT_J__")

    // Add the new dummy fields
    val newLeft = addDummyFields(pipe, leftFields, rightReplication, leftReplication)
    val newRight = addDummyFields(otherPipe, rightFields, leftReplication, rightReplication, swap = true)

    val leftJoinFields = Fields.join(fs._1, leftFields)
    val rightJoinFields = Fields.join(fs._2, rightFields)

    newLeft
      .joinWithSmaller((leftJoinFields, rightJoinFields), newRight, joiners, reducers)
      .discard(leftFields)
      .discard(rightFields)
  }

  /*
   * Adds one random field and one replica field.
   */
  private def addDummyFields(p : Pipe, f : Fields, k1 : Int, k2 : Int, swap : Boolean = false) : Pipe = {
    p.flatMap(() -> f) { u : Unit =>
      val i = if(k1 == 1 ) 0 else (new Random()).nextInt(k1 - 1)
      (0 until k2).map{ j =>
        if(swap) (j, i) else (i, j)
      }
    }
  }

  private def assertValidJoinMode(joiners : (JoinMode, JoinMode), left : Int, right : Int) {
    (joiners, left, right) match {
      case ((InnerJoinMode, InnerJoinMode), _, _) => true
      case ((InnerJoinMode, OuterJoinMode), 1, _) => true
      case ((OuterJoinMode, InnerJoinMode), _, 1) => true
      case (j, l, r) =>
        throw new InvalidJoinModeException(
          "you cannot use joiner " + j + " with left replication " + l + " and right replication " + r
        )
    }
  }
}

class InvalidJoinModeException(args : String) extends Exception(args)
