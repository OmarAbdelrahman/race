/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.track

import gov.nasa.race.geo.{GeoPosition, GreatCircle, LatLonPos}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import gov.nasa.race.util.StringUtils
import org.joda.time.DateTime

import scala.concurrent.duration.Duration
import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

trait TrackQueryContext {
  def queryDate: DateTime
  def queryTrack(id: String): Option[TrackedObject]
  def queryLocation(id: String): Option[GeoPosition]
  def reportQueryError(msg: String): Unit
}

//--- data model
trait TrackFilter {
  def pass(f: TrackedObject)(implicit ctx: TrackQueryContext): Boolean
}

/**
  * scriptable track queries - this is the data structure that represents an AST
  * which can be used in a Interpreter-like pattern to determine if tracks match
  * certain criteria.
  */
object TrackQuery {

  // note this is not sealed, subclasses can add additional filters

  //--- pseudo filters
  object AllFilter extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = true
    override def toString = "All"
  }
  object NoneFilter extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = false
    override def toString = "None"
  }

  //--- id filters
  class CsFilter (regex: Regex) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      regex.findFirstIn(f.cs).isDefined
    }
    override def toString = s"Cs($regex)"
  }

  //--- position filters
  class WithinRadiusFilter (pos: LatLonPos, dist: Length) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      GreatCircle.distance(f.position,pos) < dist
    }
    override def toString = s"WithinRadius($pos,$dist)"
  }
  class OutsideRadiusFilter (pos: LatLonPos, dist: Length) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      GreatCircle.distance(f.position,pos) > dist
    }
    override def toString = s"OutsideRadius($pos,$dist)"
  }
  class ProximityFilter(cs: String, dist: Length) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryTrack(cs) match {
        case Some(otherFlight) => GreatCircle.distance(f.position,otherFlight.position) < dist
        case None => false
      }
    }
    override def toString = s"Proximity($cs,$dist)"
  }

  //--- time filters
  class OlderDateFilter (d: DateTime) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = d.isAfter(f.date)
    override def toString = s"Older($d)"
  }
  class YoungerDateFilter (d: DateTime) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = d.isBefore(f.date)
    override def toString = s"Younger($d)"
  }
  class WithinDurationFilter (dur: Duration) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryDate.getMillis - f.date.getMillis < dur.toMillis
    }
    override def toString = s"DateWithin($dur)"
  }
  class OutsideDurationFilter (dur: Duration) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryDate.getMillis - f.date.getMillis > dur.toMillis
    }
    override def toString = s"DateOutside($dur)"
  }

  //--- composed filters
  class And (a: TrackFilter, b: TrackFilter) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = a.pass(f) && b.pass(f)
    override def toString = s"And($a,$b)"
  }
  class Or (a: TrackFilter, b: TrackFilter) extends TrackFilter {
    override def pass(f: TrackedObject)(implicit ctx:TrackQueryContext) = a.pass(f) || b.pass(f)
    override def toString = s"Or($a,$b)"
  }
}

class TrackQueryParser(val ctx: TrackQueryContext)  extends RegexParsers {
  import TrackQuery._

  type Query = TrackFilter

  //--- terminal symbols
  def GLOB: Parser[String] ="""[a-zA-Z0-9\*]+""".r ^^ { _.toString }
  def NUM: Parser[Double] = """\d+(\.\d*)?""".r ^^ { _.toDouble }
  def LONG: Parser[Long] = """\d+""".r ^^ { _.toLong }
  def ID: Parser[String] = """[a-zA-Z0-9]+""".r ^^ { _.toString }
  def DURATION: Parser[Duration] = """\d+(?:s|min\h)""".r ^^ { Duration.create(_) }

  //--- non-terminals

  def expr: Parser[Query] = spec ~ opt(opt("&" | "|") ~ spec) ^^ {
    case p1 ~ None => p1
    case p1 ~ Some(None ~ p2) => new And(p1, p2)
    case p1 ~ Some(Some("&") ~ p2) => new And(p1, p2)
    case p1 ~ Some(Some("|") ~ p2) => new Or(p1, p2)
    case p1 ~ Some(op@Some(_) ~ p2) => throw new Exception(s"invalid operator $op")  // ?? 2.12 warning - check if bogus
  }

  // this is the main extension point - override to add more filters
  def spec: Parser[Query] = allSpec | noneSpec | csSpec | posSpec | timeSpec | "(" ~> expr <~ ")"

  def allSpec: Parser[Query] = ("all" | "*") ^^^ { AllFilter }

  def noneSpec: Parser[Query] = "none" ^^^ { NoneFilter }

  def csSpec: Parser[Query] = "cs" ~ "=" ~ GLOB ^^ {
    case _ ~ _ ~ glob => new CsFilter(StringUtils.globToRegex(glob))
  }

  def posSpec: Parser[Query] = "pos" ~ "<" ~ ID ~ "+" ~ NUM ^^ {
    case _ ~ _ ~ id ~ _ ~ num =>
      val radius = NauticalMiles(num)
      ctx.queryLocation(id) match {
        case Some(loc) => new WithinRadiusFilter(loc.position, radius)
        case None => new ProximityFilter(id, radius) // TODO - sectors etc.
      }
  }

  def timeSpec: Parser[Query] = "t" ~ ("<" | ">") ~ DURATION ^^ {
    case _ ~ "<" ~ dur => new WithinDurationFilter(dur)
    case _ ~ ">" ~ dur => new OutsideDurationFilter(dur)
  }

  //... TODO - and more to follow

  def parseQuery (input: String): ParseResult[TrackFilter] = parseAll(expr, input)

  def apply(input: String): Query = parseAll(expr, input) match {
    case Success(result, _) => result
    case failure: NoSuccess => scala.sys.error(failure.msg)
  }
}

