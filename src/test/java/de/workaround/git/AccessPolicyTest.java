package de.workaround.git;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest
{
	private final User owner = user();

	private final User stranger = user();

	private final User collaborator = user();

	private final User orgGuest = user();

	private final User orgMember = user();

	private final User orgOwner = user();

	private final Organisation org = organisation();

	/** Policy whose collaborator lookup knows exactly one collaborator and one org with three members. */
	private final AccessPolicy policy = new AccessPolicy(
		(user, repository) -> Set.of(collaborator.id).contains(user.id),
		(user, organisation) -> Optional.ofNullable(Map.of(
			orgGuest.id, OrganisationMember.Role.GUEST,
			orgMember.id, OrganisationMember.Role.MEMBER,
			orgOwner.id, OrganisationMember.Role.OWNER).get(user.id)));

	@Test
	void ownerReadsAndWritesOwnRepository()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(owner, repo));
		assertTrue(policy.canWrite(owner, repo));
	}

	@Test
	void publicRepositoryIsWorldReadableButNotWritable()
	{
		Repository repo = repo(owner, Repository.Visibility.PUBLIC);

		assertTrue(policy.canRead(stranger, repo));
		assertTrue(policy.canRead(null, repo), "anonymous read on public repo");
		assertFalse(policy.canWrite(stranger, repo));
		assertFalse(policy.canWrite(null, repo), "anonymous never writes");
	}

	@Test
	void privateRepositoryIsOwnerOnly()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertFalse(policy.canRead(stranger, repo));
		assertFalse(policy.canRead(null, repo));
		assertFalse(policy.canWrite(stranger, repo));
	}

	@Test
	void collaboratorReadsAndWritesPrivateRepository()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(collaborator, repo));
		assertTrue(policy.canWrite(collaborator, repo));
	}

	@Test
	void collaboratorWritesPublicRepository()
	{
		Repository repo = repo(owner, Repository.Visibility.PUBLIC);

		assertTrue(policy.canWrite(collaborator, repo));
	}

	@Test
	void anonymousIsNeverTreatedAsCollaborator()
	{
		// lookups that would blow up on null users must never be consulted for anonymous requests
		AccessPolicy paranoid = new AccessPolicy((user, repository) -> {
			throw new AssertionError("collaborator lookup must not be called for anonymous users");
		}, (user, organisation) -> {
			throw new AssertionError("org role lookup must not be called for anonymous users");
		});
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertFalse(paranoid.canRead(null, repo));
		assertFalse(paranoid.canWrite(null, repo));
		assertFalse(paranoid.canAdmin(null, repo));
	}

	@Test
	void personalOwnerAdminsButCollaboratorDoesNot()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertTrue(policy.canAdmin(owner, repo));
		assertFalse(policy.canAdmin(collaborator, repo));
		assertFalse(policy.canAdmin(stranger, repo));
	}

	@Test
	void orgGuestReadsPrivateOrgRepositoryButNeverWrites()
	{
		Repository repo = orgRepo(Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(orgGuest, repo));
		assertFalse(policy.canWrite(orgGuest, repo));
		assertFalse(policy.canAdmin(orgGuest, repo));
	}

	@Test
	void orgMemberWritesButDoesNotAdmin()
	{
		Repository repo = orgRepo(Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(orgMember, repo));
		assertTrue(policy.canWrite(orgMember, repo));
		assertFalse(policy.canAdmin(orgMember, repo));
	}

	@Test
	void orgOwnerReadsWritesAndAdmins()
	{
		Repository repo = orgRepo(Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(orgOwner, repo));
		assertTrue(policy.canWrite(orgOwner, repo));
		assertTrue(policy.canAdmin(orgOwner, repo));
	}

	@Test
	void strangerCannotAccessPrivateOrgRepository()
	{
		Repository repo = orgRepo(Repository.Visibility.PRIVATE);

		assertFalse(policy.canRead(stranger, repo));
		assertFalse(policy.canWrite(stranger, repo));
		assertFalse(policy.canAdmin(stranger, repo));
	}

	@Test
	void publicOrgRepositoryIsWorldReadableButGuardedForWrites()
	{
		Repository repo = orgRepo(Repository.Visibility.PUBLIC);

		assertTrue(policy.canRead(stranger, repo));
		assertTrue(policy.canRead(null, repo));
		assertFalse(policy.canWrite(stranger, repo));
		assertTrue(policy.canWrite(orgMember, repo));
	}

	private static User user()
	{
		User user = new User();
		user.id = UUID.randomUUID();
		user.username = "u-" + user.id;
		return user;
	}

	private static Organisation organisation()
	{
		Organisation org = new Organisation();
		org.id = UUID.randomUUID();
		org.name = "o-" + org.id;
		return org;
	}

	private static Repository repo(User owner, Repository.Visibility visibility)
	{
		Repository repo = new Repository();
		repo.id = UUID.randomUUID();
		repo.ownerUser = owner;
		repo.visibility = visibility;
		return repo;
	}

	private Repository orgRepo(Repository.Visibility visibility)
	{
		Repository repo = new Repository();
		repo.id = UUID.randomUUID();
		repo.ownerOrg = org;
		repo.visibility = visibility;
		return repo;
	}

}
