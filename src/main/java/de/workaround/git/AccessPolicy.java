package de.workaround.git;

import java.util.Optional;

import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Single authorization rule set shared by HTTP, SSH and UI. Personal repositories: the owner has
 * full access, collaborators read and write. Organisation repositories: GUESTs read, MEMBERs also
 * write, OWNERs additionally administer. Public repositories are world-readable; private ones are
 * visible only to whoever holds a read grant.
 */
@ApplicationScoped
public class AccessPolicy
{
	private final CollaboratorLookup collaborators;

	private final OrganisationRoleLookup orgRoles;

	@Inject
	public AccessPolicy(CollaboratorLookup collaborators, OrganisationRoleLookup orgRoles)
	{
		this.collaborators = collaborators;
		this.orgRoles = orgRoles;
	}

	public boolean canRead(User user, Repository repository)
	{
		return repository.visibility == Repository.Visibility.PUBLIC || repository.isOwnedBy(user)
			|| isCollaborator(user, repository) || hasOrgRole(user, repository, OrganisationMember.Role.GUEST);
	}

	public boolean canWrite(User user, Repository repository)
	{
		return repository.isOwnedBy(user) || isCollaborator(user, repository)
			|| hasOrgRole(user, repository, OrganisationMember.Role.MEMBER);
	}

	/** Administrative access (delete, settings, collaborator/mirror management): personal owner or org OWNER. */
	public boolean canAdmin(User user, Repository repository)
	{
		return repository.isOwnedBy(user) || hasOrgRole(user, repository, OrganisationMember.Role.OWNER);
	}

	private boolean isCollaborator(User user, Repository repository)
	{
		return user != null && user.id != null && collaborators.isCollaborator(user, repository);
	}

	private boolean hasOrgRole(User user, Repository repository, OrganisationMember.Role atLeast)
	{
		return user != null && user.id != null && repository.ownerOrg != null
			&& orgRoles.roleOf(user, repository.ownerOrg).filter(role -> role.atLeast(atLeast)).isPresent();
	}

	/** How the policy asks whether a user holds a collaborator grant; implemented by {@link CollaboratorService}. */
	public interface CollaboratorLookup
	{
		boolean isCollaborator(User user, Repository repository);
	}

	/**
	 * How the policy asks which role a user holds in an organisation; implemented by
	 * {@link de.workaround.account.OrganisationService}.
	 */
	public interface OrganisationRoleLookup
	{
		Optional<OrganisationMember.Role> roleOf(User user, Organisation organisation);
	}

}
