package de.workaround.account;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Organisation lifecycle and membership rules: the creator becomes owner, the handle namespace is
 * shared with usernames in both directions, only owners manage members, and the last owner can
 * neither be removed nor downgraded.
 */
@QuarkusTest
class OrganisationServiceTest
{
	@Inject
	OrganisationService organisations;

	@Inject
	UsernameService usernames;

	@Inject
	GitRepositoryService repositories;

	@Inject
	OrganisationMember.Repo members;

	@Inject
	User.Repo users;

	private User persistUser()
	{
		User user = new User();
		user.oidcSub = "sub-" + UUID.randomUUID();
		user.username = "org-user-" + UUID.randomUUID().toString().substring(0, 8);
		user.persist();
		return user;
	}

	private static String uniqueName()
	{
		return "org-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	@TestTransaction
	void createMakesCreatorOwner()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), "ACME Inc.");

		assertEquals(OrganisationMember.Role.OWNER, organisations.roleOf(creator, org).orElseThrow());
		assertEquals(1, organisations.members(org).size());
	}

	@Test
	@TestTransaction
	void createRejectsNameMatchingExistingUsername()
	{
		User creator = persistUser();
		assertThrows(InvalidOrganisationException.class,
			() -> organisations.create(creator, creator.username, null));
	}

	@Test
	@TestTransaction
	void createRejectsNameMatchingExistingOrganisation()
	{
		User creator = persistUser();
		String name = uniqueName();
		organisations.create(creator, name, null);

		assertThrows(InvalidOrganisationException.class, () -> organisations.create(creator, name, null));
	}

	@Test
	@TestTransaction
	void createRejectsInvalidHandle()
	{
		User creator = persistUser();
		assertThrows(InvalidOrganisationException.class, () -> organisations.create(creator, "Bad.Name", null));
	}

	@Test
	@TestTransaction
	void usernameChoiceRejectsExistingOrganisationName()
	{
		User creator = persistUser();
		String name = uniqueName();
		organisations.create(creator, name, null);

		User newcomer = new User();
		newcomer.oidcSub = "sub-" + UUID.randomUUID();
		newcomer.persist();
		assertThrows(UsernameTakenException.class, () -> usernames.choose(newcomer, name));
	}

	@Test
	@TestTransaction
	void ownerAddsMembersWithRoles()
	{
		User creator = persistUser();
		User guest = persistUser();
		User member = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);

		organisations.addMember(creator, org, guest.username, OrganisationMember.Role.GUEST);
		organisations.addMember(creator, org, member.username, OrganisationMember.Role.MEMBER);

		assertEquals(OrganisationMember.Role.GUEST, organisations.roleOf(guest, org).orElseThrow());
		assertEquals(OrganisationMember.Role.MEMBER, organisations.roleOf(member, org).orElseThrow());
		assertEquals(3, organisations.members(org).size());
	}

	@Test
	@TestTransaction
	void addingUnknownUsernameFails()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);

		assertThrows(InvalidOrganisationException.class,
			() -> organisations.addMember(creator, org, "does-not-exist", OrganisationMember.Role.MEMBER));
	}

	@Test
	@TestTransaction
	void addingExistingMemberFails()
	{
		User creator = persistUser();
		User member = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		organisations.addMember(creator, org, member.username, OrganisationMember.Role.MEMBER);

		assertThrows(InvalidOrganisationException.class,
			() -> organisations.addMember(creator, org, member.username, OrganisationMember.Role.GUEST));
	}

	@Test
	@TestTransaction
	void nonOwnerCannotManageMembers()
	{
		User creator = persistUser();
		User member = persistUser();
		User outsider = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		organisations.addMember(creator, org, member.username, OrganisationMember.Role.MEMBER);

		assertThrows(ForbiddenOperationException.class,
			() -> organisations.addMember(member, org, outsider.username, OrganisationMember.Role.GUEST));
		assertThrows(ForbiddenOperationException.class,
			() -> organisations.removeMember(member, org, creator.username));
		assertThrows(ForbiddenOperationException.class,
			() -> organisations.changeRole(member, org, creator.username, OrganisationMember.Role.MEMBER));
	}

	@Test
	@TestTransaction
	void ownerChangesRole()
	{
		User creator = persistUser();
		User member = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		organisations.addMember(creator, org, member.username, OrganisationMember.Role.GUEST);

		organisations.changeRole(creator, org, member.username, OrganisationMember.Role.OWNER);

		assertEquals(OrganisationMember.Role.OWNER, organisations.roleOf(member, org).orElseThrow());
	}

	@Test
	@TestTransaction
	void lastOwnerCannotBeRemoved()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);

		assertThrows(InvalidOrganisationException.class,
			() -> organisations.removeMember(creator, org, creator.username));
	}

	@Test
	@TestTransaction
	void lastOwnerCannotBeDowngraded()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);

		assertThrows(InvalidOrganisationException.class,
			() -> organisations.changeRole(creator, org, creator.username, OrganisationMember.Role.MEMBER));
	}

	@Test
	@TestTransaction
	void firstOwnerCanLeaveOnceASecondOwnerExists()
	{
		User creator = persistUser();
		User successor = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		organisations.addMember(creator, org, successor.username, OrganisationMember.Role.OWNER);

		organisations.removeMember(creator, org, creator.username);

		assertTrue(organisations.roleOf(creator, org).isEmpty());
		assertEquals(OrganisationMember.Role.OWNER, organisations.roleOf(successor, org).orElseThrow());
	}

	@Test
	@TestTransaction
	void ownerDeletesEmptyOrganisation()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);

		organisations.delete(creator, org);

		assertTrue(organisations.find(org.name).isEmpty());
	}

	@Test
	@TestTransaction
	void deleteFailsWhileOrganisationOwnsRepositories()
	{
		User creator = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		repositories.create(org, "keeper", Repository.Visibility.PUBLIC, null);

		assertThrows(InvalidOrganisationException.class, () -> organisations.delete(creator, org));
	}

	@Test
	@TestTransaction
	void nonOwnerCannotDeleteOrganisation()
	{
		User creator = persistUser();
		User member = persistUser();
		Organisation org = organisations.create(creator, uniqueName(), null);
		organisations.addMember(creator, org, member.username, OrganisationMember.Role.MEMBER);

		assertThrows(ForbiddenOperationException.class, () -> organisations.delete(member, org));
	}

}
