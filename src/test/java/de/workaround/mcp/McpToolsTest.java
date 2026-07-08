package de.workaround.mcp;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.http.AccessTokenService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class McpToolsTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void getRepositoryToolReturnsThePublicRepositoryAnonymously()
	{
		User owner = persistUser("mcp-get-" + shortId());
		service.create(owner, "mcpgotten", Repository.Visibility.PUBLIC, null);

		McpStreamableTestClient client = McpAssured.newStreamableClient()
			.setMcpPath("/mcp").build().connect();

		client.when()
			.toolsCall("getRepository", Map.of("owner", owner.username, "name", "mcpgotten"), response -> {
				assertFalse(response.isError());
				assertTrue(response.firstContent().asText().text().contains("mcpgotten"));
			})
			.thenAssertResults();
	}

	@Test
	void listRepositoriesToolSucceedsAnonymously()
	{
		User owner = persistUser("mcp-list-" + shortId());
		service.create(owner, "mcplisted", Repository.Visibility.PUBLIC, null);

		McpStreamableTestClient client = McpAssured.newStreamableClient()
			.setMcpPath("/mcp").build().connect();

		// the list tool serializes one content item per repository; assert it succeeds and returns content
		// rather than relying on ordering (other tests create public repos that sort ahead of this one)
		client.when()
			.toolsCall("listRepositories", response -> {
				assertFalse(response.isError());
				assertNotNull(response.firstContent(), "at least the just-created public repo must be listed");
			})
			.thenAssertResults();
	}

	@Test
	void createRepositoryToolWithoutTokenFails()
	{
		McpStreamableTestClient client = McpAssured.newStreamableClient()
			.setMcpPath("/mcp").build().connect();

		client.when()
			.toolsCall("createRepository", Map.of("name", "shouldnotexist"), response ->
				assertTrue(response.isError(), "creating without a token must fail"))
			.thenAssertResults();
	}

	@Test
	void createRepositoryToolWithTokenCreatesTheRepository()
	{
		User owner = persistUser("mcp-create-" + shortId());
		String token = tokenService.create(owner, "mcp").plaintext();

		McpStreamableTestClient client = McpAssured.newStreamableClient()
			.setMcpPath("/mcp")
			.setAdditionalHeaders(json -> MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer " + token))
			.build().connect();

		client.when()
			.toolsCall("createRepository", Map.of("name", "mcpmade", "visibility", "PUBLIC"), response -> {
				assertFalse(response.isError());
				assertTrue(response.firstContent().asText().text().contains("mcpmade"));
			})
			.thenAssertResults();

		assertTrue(service.find(owner.username, "mcpmade").isPresent(), "repository must be persisted");
	}

	@Test
	void currentUserToolReturnsTheTokenOwner()
	{
		User owner = persistUser("mcp-me-" + shortId());
		String token = tokenService.create(owner, "mcp").plaintext();

		McpStreamableTestClient client = McpAssured.newStreamableClient()
			.setMcpPath("/mcp")
			.setAdditionalHeaders(json -> MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer " + token))
			.build().connect();

		client.when()
			.toolsCall("currentUser", response -> {
				assertFalse(response.isError());
				assertTrue(response.firstContent().asText().text().contains(owner.username));
			})
			.thenAssertResults();
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = userRepo.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}
}
