/*
 * Copyright 2007-2011 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb
package record

import scala.language.existentials

import java.lang.reflect.Modifier
import net.liftweb._
import util._
import common._
import scala.collection.mutable.{ListBuffer}
import scala.xml._
import net.liftweb.http.js.{JsExp, JE, JsObj}
import net.liftweb.http.{SHtml, Req, LiftResponse, LiftRules}
import net.liftweb.json._
import net.liftweb.record.FieldHelpers.expectedA
import java.lang.reflect.Method
import field._
import Box._
import JE._
import Helpers._

trait JsonMetaRec[BaseRecord <: GenericRecord[BaseRecord, FieldType, MetaType] with JsonRec[BaseRecord, FieldType, MetaType], 
  FieldType <: GenericField[_, BaseRecord, MetaType] with JsonSupport[_], MetaType <: GenericMetaRecord[BaseRecord, FieldType, MetaType] with JsonMetaRec[BaseRecord, FieldType, MetaType]] {

  self: MetaType =>
    
  /**
   * Returns the JSON representation of <i>inst</i> record
   *
   * @param inst: BaseRecord
   * @return JsObj
   */
  def asJSON(inst: BaseRecord): JsObj = {
    val tups = inst.fields.map{ field =>
      field.name -> field.asJs
    }
    JsObj(tups:_*)
  }

  /**
   * Returns the JSON representation of <i>inst</i> record, converts asJValue to JsObj
   *
   * @return a JsObj
   */
  def asJsExp(inst: BaseRecord): JsExp = new JsExp {
    lazy val toJsCmd = Printer.compact(render(asJValue(inst)))
  }

  /** Encode a record instance into a JValue */
  def asJValue(rec: BaseRecord): JObject = {
    JObject(fields(rec).map(f => JField(f.name, f.asJValue)))
  }

  /** Create a record by decoding a JValue which must be a JObject */
  def fromJValue(jvalue: JValue): Box[BaseRecord] = {
    val inst = createRecord
    setFieldsFromJValue(inst, jvalue) map (_ => inst)
  }

  /** Attempt to decode a JValue, which must be a JObject, into a record instance */
  def setFieldsFromJValue(rec: BaseRecord, jvalue: JValue): Box[Unit] = {
    def fromJFields(jfields: List[JField]): Box[Unit] = {
      for {
        jfield <- jfields
        field <- rec.fieldByName(jfield.name)
      } field.setFromJValue(jfield.value)

      Full(())
    }

    jvalue match {
      case JObject(jfields) => fromJFields(jfields)
      case other => expectedA("JObject", other)
    }
  }

  /**
   * Create a record with fields populated with values from the JSON construct
   *
   * @param json - The stringified JSON object
   * @return Box[BaseRecord]
   */
  def fromJsonString(json: String): Box[BaseRecord] = {
    val inst = createRecord
    setFieldsFromJsonString(inst, json) map (_ => inst)
  }

  /**
   * Set from a Json String using the lift-json parser
   */
  def setFieldsFromJsonString(inst: BaseRecord, json: String): Box[Unit] =
    setFieldsFromJValue(inst, JsonParser.parse(json))
}

/**
 * Holds meta information and operations on a record
 */
trait GenericMetaRecord[BaseRecord <: GenericRecord[BaseRecord, FieldType, MetaType], 
  FieldType <: GenericField[_, BaseRecord, MetaType], MetaType <: GenericMetaRecord[BaseRecord, FieldType, MetaType]] {  
  self: BaseRecord =>

  def fieldControl: FieldControl[FieldType]  
    
  class FieldControl[FieldType <: AbstractField : ClassManifest] {
    def introspectField(rec: BaseRecord, method: Method)(f: (Method, FieldType) => Any) {
      method.invoke(rec) match {
        case mf: FieldType if !mf.ignoreField_? =>
          mf.setName_!(method.getName)
          f(method, mf)
        case _ =>
      }      
    }
    
    def isField(m: Method) = {
      val ret = !m.isSynthetic && manifest.runtimeClass.isAssignableFrom(m.getReturnType)
      ret      
    }
  }    
    
  private var fieldList: List[FieldHolder[FieldType]] = Nil
  private var fieldMap: Map[String, FieldHolder[FieldType]] = Map.empty

  private var lifecycleCallbacks: List[(String, Method)] = Nil

  def connectionIdentifier: ConnectionIdentifier = DefaultConnectionIdentifier

  /**
   * Set this to use your own form template when rendering a Record to a form.
   *
   * This template is any given XHtml that contains three nodes acting as placeholders such as:
   *
   * <pre>
   *
   * &lt;lift:field_label name="firstName"/&gt; - the label for firstName field will be rendered here
   * &lt;lift:field name="firstName"/&gt; - the firstName field will be rendered here (typically an input field)
   * &lt;lift:field_msg name="firstName"/&gt; - the <lift:msg> will be rendered here having the id given by
   *                                             uniqueFieldId of the firstName field.
   *
   *
   * Example.
   *
   * Having:
   *
   * class MyRecord extends Record[MyRecord] {
   *
   * 	def meta = MyRecordMeta
   *
   * 	object firstName extends StringField(this, "John")
   *
   * }
   *
   * object MyRecordMeta extends MyRecord with MetaRecord[MyRecord] {
   *  override def mutable_? = false
   * }
   *
   * ...
   *
   * val rec = MyRecordMeta.createRecord.firstName("McLoud")
   *
   * val template =
   * &lt;div&gt;
   * 	&lt;div&gt;
   * 		&lt;div&gt;&lt;lift:field_label name="firstName"/&gt;&lt;/div&gt;
   * 		&lt;div&gt;&lt;lift:field name="firstName"/&gt;&lt;/div&gt;
   * 		&lt;div&gt;&lt;lift:field_msg name="firstName"/&gt;&lt;/div&gt;
   * 	&lt;/div&gt;
   * &lt;/div&gt;
   *
   * MyRecordMeta.formTemplate = Full(template)
   * rec.toForm((r:MyRecord) => println(r));
   *
   * </pre>
   *
   */
  var formTemplate: Box[NodeSeq] = Empty

  protected val rootClass = this.getClass.getSuperclass

  private def isLifecycle(m: Method) = classOf[LifecycleCallbacks].isAssignableFrom(m.getReturnType)

  private def isField(m: Method) = fieldControl.isField(m)

  def introspect(rec: BaseRecord, methods: Array[Method])(f: (Method, FieldType) => Any): Unit = {

    // find all the potential fields
    val potentialFields = methods.toList.filter(isField)

    // any fields with duplicate names get put into a List
    val map: Map[String, List[Method]] = potentialFields.foldLeft[Map[String, List[Method]]](Map()){
      case (map, method) => val name = method.getName
      map + (name -> (method :: map.getOrElse(name, Nil)))
    }

    // sort each list based on having the most specific type and use that method
    val realMeth = map.values.map(_.sortWith {
      case (a, b) => !a.getReturnType.isAssignableFrom(b.getReturnType)
    }).map(_.head)

    for (v <- realMeth) {
    	fieldControl.introspectField(rec, v)(f)
    }

  }

  this.runSafe {
    val tArray = new ListBuffer[FieldHolder[FieldType]]

    val methods = rootClass.getMethods

    lifecycleCallbacks = (for (v <- methods
                              if v.getName != "meta" && isLifecycle(v)) yield (v.getName, v)).toList

    introspect(this, methods) {
      case (v, mf) => tArray += FieldHolder(mf.name, v, mf)
    }

    fieldList = {
      val ordered = fieldOrder.flatMap(f => tArray.find(_.metaField == f))
      ordered ++ (tArray -- ordered)
    }

    fieldMap = Map() ++ fieldList.map(i => (i.name, i))
  }

  /**
   * Specifies if this Record is mutable or not
   */
  def mutable_? = true

  /**
   * Creates a new record
   */
  def createRecord: BaseRecord = {
    val rec = instantiateRecord
    rec runSafe {
      fieldList.foreach(fh => fh.field(rec).setName_!(fh.name))
    }
    rec
  }

  /** Make a new record instance. This method can be overridden to provide caching behavior or what have you. */
  protected def instantiateRecord: BaseRecord = rootClass.newInstance.asInstanceOf[BaseRecord]

  /**
   * Returns the HTML representation of inst Record.
   *
   * @param inst - th designated Record
   * @return a NodeSeq
   */
  def toXHtml(inst: BaseRecord): NodeSeq = fieldList.flatMap(_.field(inst).toXHtml ++ Text("\n"))


  /**
   * Validates the inst Record by calling validators for each field
   *
   * @param inst - the Record to be validated
   * @return a List of FieldError. If this list is empty you can assume that record was validated successfully
   */
  def validate(inst: BaseRecord): List[FieldError] = {
    foreachCallback(inst, _.beforeValidation)
    try{
      fieldList.flatMap(_.field(inst).validate)
    } finally {
      foreachCallback(inst, _.afterValidation)
    }
  }

  def foreachCallback(inst: BaseRecord, f: LifecycleCallbacks => Any) {
    lifecycleCallbacks.foreach(m => f(m._2.invoke(inst).asInstanceOf[LifecycleCallbacks]))
  }

  /**
   * Returns the XHTML representation of inst Record. If formTemplate is set,
   * this template will be used otherwise a default template is considered.
   *
   * @param inst - the record to be rendered
   * @return the XHTML content as a NodeSeq
   */
  def toForm(inst: BaseRecord): NodeSeq = {
    formTemplate match {
      case Full(template) => toForm(inst, template)
      case _ => fieldList.flatMap(_.field(inst).toForm.openOr(NodeSeq.Empty) ++ Text("\n"))
    }
  }

  /**
   * Returns the XHTML representation of inst Record. You must provide the Node template
   * to represent this record in the proprietary layout.
   *
   * @param inst - the record to be rendered
   * @param template - The markup template forthe form. See also the formTemplate variable
   * @return the XHTML content as a NodeSeq
   */
  def toForm(inst: BaseRecord, template: NodeSeq): NodeSeq = {
    template match {
      case e @ <lift:field_label>{_*}</lift:field_label> => e.attribute("name") match {
          case Some(name) => fieldByName(name.toString, inst).map(_.label).openOr(NodeSeq.Empty)
          case _ => NodeSeq.Empty
        }

      case e @ <lift:field>{_*}</lift:field> => e.attribute("name") match {
          case Some(name) => fieldByName(name.toString, inst).flatMap(_.toForm).openOr(NodeSeq.Empty)
          case _ => NodeSeq.Empty
        }

      case e @ <lift:field_msg>{_*}</lift:field_msg> => e.attribute("name") match {
          case Some(name) => fieldByName(name.toString, inst).map(_.uniqueFieldId match {
                case Full(id) => <lift:msg id={id}/>
                case _ => NodeSeq.Empty
              }).openOr(NodeSeq.Empty)
          case _ => NodeSeq.Empty
        }

      case elem: Elem =>
        elem.copy(child = toForm(inst, elem.child.flatMap(n => toForm(inst, n))))

      case s : Seq[_] => s.flatMap(e => e match {
            case elem: Elem =>
              elem.copy(child = toForm(inst, elem.child.flatMap(n => toForm(inst, n))))

            case x => x
          })

    }
  }

  /**
   * Get a field by the field name
   * @param fieldName -- the name of the field to get
   * @param actual -- the instance to get the field on
   *
   * @return Box[The Field] (Empty if the field is not found)
   */
  def fieldByName(fieldName: String, inst: BaseRecord): Box[FieldType] = {
    Box(fieldMap.get(fieldName).map(_.field(inst)))
  }

  /**
   * Prepend a DispatchPF function to LiftRules.dispatch. If the partial function is defined for a give Req
   * it will construct a new Record based on the HTTP query string parameters
   * and will pass this Record to the function returned by func parameter.
   *
   * @param func - a PartialFunction for associating a request with a user-provided function and the proper Record
   */
  def prependDispatch(func: PartialFunction[Req, BaseRecord => Box[LiftResponse]])= {
    LiftRules.dispatch.prepend (makeFunc(func))
  }

  /**
   * Append a DispatchPF function to LiftRules.dispatch. If the partial function is defined for a give Req
   * it will construct a new Record based on the HTTP query string parameters
   * and will pass this Record to the function returned by func parameter.
   *
   * @param func - a PartialFunction for associating a request with a user-provided function and the proper Record
   */
  def appendDispatch(func: PartialFunction[Req, BaseRecord => Box[LiftResponse]])= {
    LiftRules.dispatch.append (makeFunc(func))
  }


  private def makeFunc(func: PartialFunction[Req, BaseRecord => Box[LiftResponse]]) = new PartialFunction[Req, () => Box[LiftResponse]] {

      def isDefinedAt(r: Req): Boolean = func.isDefinedAt(r)

      def apply(r: Req): () => Box[LiftResponse] = {
        val rec = fromReq(r)
        () => func(r)(rec)
      }
    }

  /**
   * Create a record with fields populated with values from the request
   *
   * @param req - The Req to read from
   * @return the created record
   */
  def fromReq(r: Req): BaseRecord = {
    val inst = createRecord
    setFieldsFromReq(inst, r)
    inst
  }

  /**
   * Populate the fields of the record with values from the request
   *
   * @param inst - The record to populate
   * @param req - The Req to read from
   */
  def setFieldsFromReq(inst: BaseRecord, req: Req) {
    for(fh <- fieldList){
      fh.field(inst).setFromAny(req.param(fh.name))
    }
  }

  /**
   * Populate the fields of the record with values from an existing record
   *
   * @param inst - The record to populate
   * @param rec - The Record to read from
   */
  def setFieldsFromRecord(inst: BaseRecord, rec: BaseRecord) {
    for {
      fh <- fieldList
      fld <- rec.fieldByName(fh.name)
    } {
      fh.field(inst).setFromAny(fld.valueBox)
    }
  }

  def createWithMutableGenericField[ValueType, FieldType <: GenericField[ValueType, BaseRecord, MetaType]](original: BaseRecord,
                                        field: FieldType,
                                        newValue: Box[ValueType]): BaseRecord = {
    val rec = createRecord

    for (fh <- fieldList) {
      val recField = fh.field(rec)
      if (fh.name == field.name)
        recField.asInstanceOf[GenericField[ValueType, BaseRecord, MetaType]].setBox(newValue)
      else
        recField.setFromAny(fh.field(original).valueBox)
    }
    rec
  }   
  
  def copy(rec: BaseRecord): BaseRecord = {
    val inst = createRecord
    setFieldsFromRecord(inst, rec)
    inst
  }

  /**
   * Defines the order of the fields in this record
   *
   * @return a List of Field
   */
  def fieldOrder: List[FieldType] = Nil

  /**
   * Renamed from fields() due to a clash with fields() in Record. Use this method
   * to obtain a list of fields defined in the meta companion objects. Possibly a
   * breaking change? (added 14th August 2009, Tim Perrett)
   *
   * @see Record
   */
  def metaFields() : List[FieldType] = fieldList.map(_.metaField)

  /**
   * Obtain the fields for a particular Record or subclass instance by passing
   * the instance itself.
   * (added 14th August 2009, Tim Perrett)
   */
  def fields(rec: BaseRecord) : List[FieldType] = fieldList.map(_.field(rec))

  case class FieldHolder[FieldType](name: String, method: Method, metaField: FieldType) {
    def field(inst: BaseRecord): FieldType = method.invoke(inst).asInstanceOf[FieldType]
  }

  def dirty_?(inst: BaseRecord): Boolean = !fields(inst).filter(_.dirty_?).isEmpty
}

trait MetaRecord[BaseRecord <: Record[BaseRecord]] extends GenericMetaRecord[BaseRecord, Field[_, BaseRecord], MetaRecord[BaseRecord]] with JsonMetaRec[BaseRecord, Field[_, BaseRecord], MetaRecord[BaseRecord]] {
  self: MetaRecord[BaseRecord] with BaseRecord =>
    
  def fieldControl = new FieldControl[Field[_, BaseRecord]]
  
  /**
   * Creates a new record, setting the value of the fields from the original object but
   * applying the new value for the specific field
   *
   * @param - original the initial record
   * @param - field the new mutated field
   * @param - the new value of the field
   */
  def createWithMutableField[FieldType](original: BaseRecord,
                                        field: Field[FieldType, BaseRecord],
                                        newValue: Box[FieldType]): BaseRecord = createWithMutableGenericField(original, field, newValue)
}
