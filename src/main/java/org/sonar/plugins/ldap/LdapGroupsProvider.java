/*
 * Sonar LDAP Plugin
 * Copyright (C) 2009 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.ldap;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.security.ExternalGroupsProvider;
import org.sonar.api.utils.SonarException;
import org.sonar.plugins.ldap.ldapentryprocessor.LdapEntryProcessor;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchResult;
import java.util.*;

/**
 * @author Evgeny Mandrikov
 */
public class LdapGroupsProvider extends ExternalGroupsProvider
{

    private static final Logger LOG = LoggerFactory.getLogger(LdapGroupsProvider.class);

    private final Map<String, LdapContextFactory> contextFactories;
    private final Map<String, LdapUserMapping> userMappings;
    private final Map<String, LdapGroupMapping> groupMappings;

    public LdapGroupsProvider(Map<String, LdapContextFactory> contextFactories, Map<String, LdapUserMapping> userMappings, Map<String, LdapGroupMapping> groupMapping)
    {
        this.contextFactories = contextFactories;
        this.userMappings = userMappings;
        this.groupMappings = groupMapping;
    }

    /**
     * @throws SonarException if unable to retrieve groups
     */
    public Collection<String> doGetGroups(String username)
    {
        checkPrerequisites(username);
        Set<String> groups = Sets.newHashSet();
        List<SonarException> sonarExceptions = new ArrayList<SonarException>();
        for (String serverKey : userMappings.keySet())
        {
            if (!groupMappings.containsKey(serverKey))
            {
                // No group mapping for this ldap instance.
                continue;
            }
            SearchResult searchResult = searchUserGroups(username, sonarExceptions, serverKey);

            if (searchResult != null)
            {
                try
                {
                    LdapSearch ldapSearch = groupMappings
                            .get(serverKey)
                            .createSearch(contextFactories.get(serverKey), searchResult);
                    groups.addAll(mapGroups(serverKey, ldapSearch));
                    // if no exceptions occur, we found the user and his groups and mapped his details.
                    break;
                }
                catch (NamingException e)
                {
                    // just in case if Sonar silently swallowed exception
                    LOG.debug(e.getMessage(), e);
                    sonarExceptions.add(new SonarException("Unable to retrieve groups for user " + username + " in " + serverKey, e));
                }
            }
            else
            {
                // user not found
                continue;
            }
        }
        checkResults(groups, sonarExceptions);
        return groups;
    }

    private void checkResults(Set<String> groups, List<SonarException> sonarExceptions)
    {
        if (groups.isEmpty() && !sonarExceptions.isEmpty())
        {
            // No groups found and there is an exception so there is a reason the user could not be found.
            throw sonarExceptions.iterator().next();
        }
    }

    private void checkPrerequisites(String username)
    {
        if (userMappings.isEmpty() || groupMappings.isEmpty())
        {
            throw new SonarException("Unable to retrieve details for user " + username + ": No user or group mapping found.");
        }
    }

    private SearchResult searchUserGroups(String username, List<SonarException> sonarExceptions, String serverKey)
    {
        SearchResult searchResult = null;
        try
        {
            LOG.debug("Requesting groups for user {}", username);

            searchResult = userMappings.get(serverKey).createSearch(contextFactories.get(serverKey), username)
                    .returns(groupMappings.get(serverKey).getRequiredUserAttributes())
                    .findUnique();
        }
        catch (NamingException e)
        {
            // just in case if Sonar silently swallowed exception
            LOG.debug(e.getMessage(), e);
            sonarExceptions.add(new SonarException("Unable to retrieve groups for user " + username + " in " + serverKey, e));
        }
        return searchResult;
    }

    /**
     * Map all the groups.
     *
     * @param serverKey  The index we use to choose the correct {@link org.sonar.plugins.ldap.LdapGroupMapping}.
     * @param ldapSearch
     * @return A {@link Collection} of groups the user is member of.
     * @throws NamingException
     */
    private Collection<String> mapGroups(final String serverKey, LdapSearch ldapSearch) throws NamingException
    {
        final Set<String> groups = new HashSet<String>();

        ldapSearch.iterateSearchResults(new LdapEntryProcessor()
        {
            @Override
            public void processLdapSearchResult(LdapSearchResultContext ldapSearchResultContext, Object next) throws NamingException
            {
                SearchResult obj = (SearchResult) next;
                Attributes attributes = obj.getAttributes();
                String groupId = null;
                groupId = (String) attributes.get(groupMappings.get(serverKey).getIdAttribute()).get();
                groups.add(groupId);
            }
        });

        return groups;
    }

}
