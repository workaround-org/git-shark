package de.workaround.nativeimg;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.GcConfig;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * jgit reads git config values such as {@code gc.packRefs} by reflectively calling
 * {@code values()} on the corresponding enum. In a native image every such enum must be
 * listed in reflect-config.json, otherwise startup fails with NoSuchMethodException.
 * This guards the enums nested in {@link GcConfig} against jgit upgrades that add new ones.
 */
class JgitReflectConfigTest
{
	private static final String CONFIG = "META-INF/native-image/de.workaround/git-shark/reflect-config.json";

	@Test
	void gcConfigEnumsAreRegisteredForReflection() throws Exception
	{
		JsonNode entries;
		try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(CONFIG))
		{
			assertTrue(in != null, "reflect-config.json not found on classpath: " + CONFIG);
			entries = new ObjectMapper().readTree(in);
		}

		List<String> missing = new ArrayList<>();
		for (Class<?> nested : GcConfig.class.getDeclaredClasses())
		{
			if (!nested.isEnum())
			{
				continue;
			}
			if (!hasValuesEntry(entries, nested.getName()))
			{
				missing.add(nested.getName());
			}
		}

		if (!missing.isEmpty())
		{
			fail("These jgit enums must be registered in reflect-config.json with a values() method "
				+ "or the native image fails to start: " + missing);
		}
	}

	private static boolean hasValuesEntry(JsonNode entries, String className)
	{
		for (JsonNode entry : entries)
		{
			if (!className.equals(entry.path("name").asText()))
			{
				continue;
			}
			for (JsonNode method : entry.path("methods"))
			{
				if ("values".equals(method.path("name").asText()))
				{
					return true;
				}
			}
		}
		return false;
	}
}
