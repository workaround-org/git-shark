package de.workaround.account;

import java.util.List;
import java.util.Optional;

import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Organisation lifecycle and membership. The creator becomes OWNER; only owners manage members and
 * settings; an organisation always keeps at least one owner. Also serves as the
 * {@link AccessPolicy}'s org-role lookup, so HTTP, SSH and UI authorization all see the same
 * memberships.
 */
@ApplicationScoped
public class OrganisationService implements AccessPolicy.OrganisationRoleLookup
{
	@Inject
	Organisation.Repo organisations;

	@Inject
	OrganisationMember.Repo members;

	@Inject
	User.Repo users;

	@Inject
	Repository.Repo repositories;

	@Inject
	HandleService handles;

	@Transactional
	public Organisation create(User creator, String name, String displayName)
	{
		String candidate = name == null ? "" : name.trim();
		if (!HandleService.valid(candidate))
		{
			throw new InvalidOrganisationException(
				"Organisation names must be 1-39 characters, lowercase letters, digits or hyphens, and start with a letter or digit.");
		}
		if (handles.takenByOther(candidate, null))
		{
			throw new InvalidOrganisationException("That name is already taken.");
		}
		Organisation organisation = new Organisation();
		organisation.name = candidate;
		organisation.displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
		organisation.persist();

		OrganisationMember membership = new OrganisationMember();
		membership.organisation = organisation;
		membership.user = users.findById(creator.id);
		membership.role = OrganisationMember.Role.OWNER;
		membership.persist();
		return organisation;
	}

	public Optional<Organisation> find(String name)
	{
		return organisations.findByName(name);
	}

	@Transactional
	public OrganisationMember addMember(User actor, Organisation organisation, String username,
		OrganisationMember.Role role)
	{
		requireOwner(actor, organisation);
		User user = users.findByUsername(username == null ? "" : username.strip())
			.orElseThrow(() -> new InvalidOrganisationException("No user with that username exists."));
		if (members.findByOrganisationAndUser(organisation, user).isPresent())
		{
			throw new InvalidOrganisationException("That user is already a member.");
		}
		OrganisationMember membership = new OrganisationMember();
		membership.organisation = organisation;
		membership.user = user;
		membership.role = role;
		membership.persist();
		return membership;
	}

	@Transactional
	public void changeRole(User actor, Organisation organisation, String username, OrganisationMember.Role role)
	{
		requireOwner(actor, organisation);
		OrganisationMember membership = requireMembership(organisation, username);
		if (membership.role == OrganisationMember.Role.OWNER && role != OrganisationMember.Role.OWNER)
		{
			requireAnotherOwner(organisation);
		}
		membership.role = role;
	}

	@Transactional
	public void removeMember(User actor, Organisation organisation, String username)
	{
		requireOwner(actor, organisation);
		OrganisationMember membership = requireMembership(organisation, username);
		if (membership.role == OrganisationMember.Role.OWNER)
		{
			requireAnotherOwner(organisation);
		}
		members.deleteById(membership.id);
	}

	@Transactional
	public void delete(User actor, Organisation organisation)
	{
		requireOwner(actor, organisation);
		if (repositories.countByOwnerOrg(organisation) > 0)
		{
			throw new InvalidOrganisationException(
				"The organisation still owns repositories. Delete or transfer them first.");
		}
		// remove memberships through the ORM (not only the DB cascade) so entities already loaded in
		// this session don't keep referencing the removed organisation at the next flush
		members.findByOrganisation(organisation).forEach(membership -> members.deleteById(membership.id));
		organisations.deleteById(organisation.id);
	}

	public List<OrganisationMember> members(Organisation organisation)
	{
		return members.findByOrganisation(organisation);
	}

	/** Organisations in which the user holds the OWNER role — the ones they may create repositories in. */
	public List<Organisation> ownedBy(User user)
	{
		return members.findOwnerships(user).stream().map(membership -> membership.organisation).toList();
	}

	@Override
	public Optional<OrganisationMember.Role> roleOf(User user, Organisation organisation)
	{
		return members.findByOrganisationAndUser(organisation, user).map(membership -> membership.role);
	}

	private OrganisationMember requireMembership(Organisation organisation, String username)
	{
		return users.findByUsername(username == null ? "" : username.strip())
			.flatMap(user -> members.findByOrganisationAndUser(organisation, user))
			.orElseThrow(() -> new InvalidOrganisationException("That user is not a member."));
	}

	private void requireAnotherOwner(Organisation organisation)
	{
		if (members.countOwners(organisation) <= 1)
		{
			throw new InvalidOrganisationException(
				"Cannot remove or downgrade the last owner of an organisation.");
		}
	}

	private void requireOwner(User actor, Organisation organisation)
	{
		boolean owner = actor != null && actor.id != null
			&& roleOf(actor, organisation).filter(role -> role == OrganisationMember.Role.OWNER).isPresent();
		if (!owner)
		{
			throw new ForbiddenOperationException("Only an organisation owner can do that");
		}
	}

}
