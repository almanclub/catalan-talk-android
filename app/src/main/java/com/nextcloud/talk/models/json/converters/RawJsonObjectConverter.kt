/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.models.json.converters

import com.bluelinelabs.logansquare.LoganSquare
import com.bluelinelabs.logansquare.typeconverters.TypeConverter
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter

/**
 * Keeps a JSON object field as its raw JSON text, so it can be stored as is and validated later.
 * Anything other than an object (null, string, array, number) is read as null.
 */
class RawJsonObjectConverter : TypeConverter<String?> {
    override fun parse(jsonParser: JsonParser): String? {
        if (jsonParser.currentToken != JsonToken.START_OBJECT) {
            jsonParser.skipChildren()
            return null
        }
        val writer = StringWriter()
        LoganSquare.JSON_FACTORY.createGenerator(writer).use { generator ->
            generator.copyCurrentStructure(jsonParser)
        }
        return writer.toString()
    }

    override fun serialize(
        value: String?,
        fieldName: String,
        writeFieldNameForObject: Boolean,
        jsonGenerator: JsonGenerator
    ) {
        if (value.isNullOrBlank()) {
            return
        }
        if (writeFieldNameForObject) {
            jsonGenerator.writeFieldName(fieldName)
        }
        jsonGenerator.writeRawValue(value)
    }
}
