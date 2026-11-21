package com.usebruno.intellij.flows;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;

final class JsonSupport {
    static void validate(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            if (reader.peek() == JsonToken.END_DOCUMENT) throw new IllegalArgumentException("The JSON body is empty.");
            reader.skipValue();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Unexpected data after the JSON body.");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("The request body is not valid JSON."); }
    }
}
