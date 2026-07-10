package de.workaround.git;

import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.account.OrganisationService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Organisation-owned repositories resolve through the same {@code {owner}/{name}} lookup as user
 * repositories, live on disk under the organisation's id, and are administered by org owners only.
 */
@QuarkusTest
class OrgRepositoryTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	OrganisationService organisations;

	@Inject
	User.Repo users;

	@Test
	void orgRepositoryResolvesByOrgHandle()
	{
		Organisation org = orgOwnedBy(persistUser());
		Repository repo = service.create(org, "resolve-me", Repository.Visibility.PUBLIC, null);

		Repository found = service.find(org.name, "resolve-me").orElseThrow();
		assertEquals(repo.id, found.id);
		assertEquals(org.name, found.ownerHandle());
	}

	@Test
	void dotGitSuffixResolvesForOrgRepositories()
	{
		Organisation org = orgOwnedBy(persistUser());
		service.create(org, "suffixed", Repository.Visibility.PUBLIC, null);

		assertTrue(service.find(org.name, "suffixed.git").isPresent());
	}

	@Test
	void orgRepositoryStorageIsKeyedByOrgId()
	{
		Organisation org = orgOwnedBy(persistUser());
		Repository repo = service.create(org, "on-disk", Repository.Visibility.PUBLIC, null);

		assertTrue(service.repositoryPath(repo).toString().contains(org.id.toString()));
		assertTrue(Files.isDirectory(service.repositoryPath(repo)));
	}

	@Test
	void duplicateNamePerOrganisationIsRejected()
	{
		Organisation org = orgOwnedBy(persistUser());
		service.create(org, "dup", Repository.Visibility.PUBLIC, null);

		assertThrows(RepositoryAlreadyExistsException.class,
			() -> service.create(org, "dup", Repository.Visibility.PUBLIC, null));
	}

	@Test
	void orgOwnerDeletesRepositoryButMemberCannot()
	{
		User creator = persistUser();
		User member = persistUser();
		Organisation org = orgOwnedBy(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);
		Repository repo = service.create(org, "guarded-delete", Repository.Visibility.PUBLIC, null);

		assertThrows(ForbiddenOperationException.class, () -> service.delete(member, repo));

		service.delete(creator, repo);
		assertTrue(service.find(org.name, "guarded-delete").isEmpty());
	}

	@Transactional
	User persistUser()
	{
		User user = new User();
		user.oidcSub = "sub-" + UUID.randomUUID();
		user.username = "orgrepo-" + UUID.randomUUID().toString().substring(0, 8);
		user.persist();
		return user;
	}

	@Transactional
	Organisation orgOwnedBy(User creator)
	{
		return organisations.create(creator, "orgrepo-" + UUID.randomUUID().toString().substring(0, 8), null);
	}

	@Transactional
	void addMember(User actor, Organisation org, User user, OrganisationMember.Role role)
	{
		organisations.addMember(actor, org, user.username, role);
	}

}
