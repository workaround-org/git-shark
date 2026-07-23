package de.workaround.ci;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * (De)serializes a job's outputs to/from the JSON stored in {@code action_task.outputs}. A shared,
 * thread-safe {@link ObjectMapper}; a malformed or empty value reads back as an empty map so a bad row
 * never breaks dispatch.
 */
final class ActionOutputs
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE = new TypeReference<>()
	{
	};

	private ActionOutputs()
	{
	}

	static Map<String, String> parse(String json)
	{
		if (json == null || json.isBlank())
		{
			return new LinkedHashMap<>();
		}
		try
		{
			return MAPPER.readValue(json, MAP_TYPE);
		}
		catch (Exception malformed)
		{
			return new LinkedHashMap<>();
		}
	}

	static String write(Map<String, String> outputs)
	{
		try
		{
			return MAPPER.writeValueAsString(outputs);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not serialize task outputs", e);
		}
	}
}
