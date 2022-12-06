/*
 * Copyright 2021 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.util.extension

import ca.uhn.fhir.util.UrlUtil
import com.fasterxml.jackson.databind.DeserializationFeature
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.google.android.fhir.workflow.FhirOperator
import org.hl7.fhir.r4.model.Composition
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Measure
import org.hl7.fhir.r4.model.RelatedArtifact
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.cql.engine.serializing.jackson.JsonCqlMapper
import timber.log.Timber

suspend inline fun <reified T : Resource> FhirEngine.loadResource(resourceId: String): T? {
  return try {
    this.get(resourceId)
  } catch (resourceNotFoundException: ResourceNotFoundException) {
    null
  }
}

suspend fun FhirEngine.searchCompositionByIdentifier(identifier: String): Composition? =
  this.search<Composition> {
      filter(Composition.IDENTIFIER, { value = of(Identifier().apply { value = identifier }) })
    }
    .firstOrNull()

suspend fun FhirEngine.loadLibraryAtPath(fhirOperator: FhirOperator, path: String) {
  // resource path could be Library/123 OR something like http://fhir.labs.common/Library/123
  val library =
    if (!UrlUtil.isValid(path)) get(IdType(path).idPart)
    else search<Library> { filter(Library.URL, { value = path }) }.firstOrNull()

  library?.let {
    JsonCqlMapper.getMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    it.content
      .find { attachment -> attachment.contentType.contains(Regex(".*elm.*json.*")) }
      ?.let { attachment ->
        val data =
          attachment
            .data
            .decodeToString()
            .replace("\"translatorOptions\"", "\"type\" : \"CqlToElmInfo\",\"translatorOptions\"")
            .replace("\"t\" :", "\"type\" : \"Annotation\",\"t\" :")
            .replace("\"s\" : {", "\"type\" : \"Annotation\",\"s\" : {")
            .replace("\"r\" : {", "\"type\" : \"Annotation\",\"r\" : {")
            .replace("\"errorSeverity\" : ", "\"type\" : \"Locator\",\"errorSeverity\" : ")

        attachment.data = data.encodeToByteArray()
      }
    fhirOperator.loadLib(it)

    it.relatedArtifact.forEach { loadLibraryAtPath(fhirOperator, it) }
  }
}

suspend fun FhirEngine.loadLibraryAtPath(
  fhirOperator: FhirOperator,
  relatedArtifact: RelatedArtifact
) {
  if (relatedArtifact.type.isIn(
      RelatedArtifact.RelatedArtifactType.COMPOSEDOF,
      RelatedArtifact.RelatedArtifactType.DEPENDSON
    )
  )
    loadLibraryAtPath(fhirOperator, relatedArtifact.resource)
}

suspend fun FhirEngine.loadCqlLibraryBundle(fhirOperator: FhirOperator, measurePath: String) =
  try {
    // resource path could be Measure/123 OR something like http://fhir.labs.common/Measure/123
    val measure: Measure? =
      if (UrlUtil.isValid(measurePath))
        search<Measure> { filter(Measure.URL, { value = measurePath }) }.firstOrNull()
      else get(measurePath)

    measure?.apply {
      relatedArtifact.forEach { loadLibraryAtPath(fhirOperator, it) }
      library.map { it.value }.forEach { path -> loadLibraryAtPath(fhirOperator, path) }
    }
  } catch (exception: Exception) {
    Timber.e(exception)
  }