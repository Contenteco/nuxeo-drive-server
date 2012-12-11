/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Olivier Grisel <ogrisel@nuxeo.com>
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.service.DocumentChangeFinder;
import org.nuxeo.drive.service.NuxeoDriveManager;
import org.nuxeo.drive.service.TooManyDocumentChangesException;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.ClientRuntimeException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.model.PropertyException;
import org.nuxeo.ecm.core.api.repository.Repository;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.security.SecurityException;
import org.nuxeo.ecm.platform.query.nxql.NXQLQueryBuilder;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.DefaultComponent;

import com.google.common.collect.MapMaker;

/**
 * Manage list of NuxeoDrive synchronization roots and devices for a given nuxeo
 * user.
 */
public class NuxeoDriveManagerImpl extends DefaultComponent implements
        NuxeoDriveManager {

    public static final String NUXEO_DRIVE_FACET = "DriveSynchronized";

    public static final String DRIVE_SUBSCRIBERS_PROPERTY = "drv:subscribers";

    public static final String DOCUMENT_CHANGE_LIMIT_PROPERTY = "org.nuxeo.drive.document.change.limit";

    /**
     * Cache holding the synchronization roots as a map of 2 dimension arrays:
     * the map keys are the repository names, the first element of the array is
     * a set of root references of type {@link DocumentRef} , the second one a
     * set of root paths of type {@link String}.
     */
    // TODO: upgrade to latest version of google collections to be able to limit
    // the size with a LRU policy
    ConcurrentMap<String, Map<String, Serializable[]>> cache = new MapMaker().concurrencyLevel(
            4).softKeys().softValues().expiration(10, TimeUnit.MINUTES).makeMap();

    // TODO: make this overridable with an extension point
    protected DocumentChangeFinder documentChangeFinder = new AuditDocumentChangeFinder();

    @Override
    public void registerSynchronizationRoot(String userName,
            DocumentModel newRootContainer, CoreSession session)
            throws PropertyException, ClientException, SecurityException {
        if (!newRootContainer.hasFacet(NUXEO_DRIVE_FACET)) {
            newRootContainer.addFacet(NUXEO_DRIVE_FACET);
        }
        if (!newRootContainer.isFolder() || newRootContainer.isProxy()
                || newRootContainer.isVersion()) {
            throw new ClientException(String.format(
                    "Document '%s' (%s) is not a suitable synchronization root"
                            + " as it is either not folderish or is a readonly"
                            + " proxy or archived version.",
                    newRootContainer.getTitle(), newRootContainer.getRef()));
        }
        UserManager userManager = Framework.getLocalService(UserManager.class);
        if (!session.hasPermission(userManager.getPrincipal(userName),
                newRootContainer.getRef(), SecurityConstants.ADD_CHILDREN)) {
            throw new SecurityException(String.format(
                    "%s has no permission to create content in '%s' (%s).",
                    userName, newRootContainer.getTitle(),
                    newRootContainer.getRef()));
        }
        String[] subscribers = (String[]) newRootContainer.getPropertyValue(DRIVE_SUBSCRIBERS_PROPERTY);
        if (subscribers == null) {
            subscribers = new String[] { userName };
        } else {
            if (Arrays.binarySearch(subscribers, userName) < 0) {
                String[] old = subscribers;
                subscribers = new String[old.length + 1];
                for (int i = 0; i < old.length; i++) {
                    subscribers[i] = old[i];
                }
                subscribers[old.length] = userName;
                Arrays.sort(subscribers);
            }
        }
        newRootContainer.setPropertyValue(DRIVE_SUBSCRIBERS_PROPERTY,
                (Serializable) subscribers);
        session.saveDocument(newRootContainer);
        session.save();
        cache.clear();
    }

    @Override
    public void unregisterSynchronizationRoot(String userName,
            DocumentModel rootContainer, CoreSession session)
            throws PropertyException, ClientException {
        if (!rootContainer.hasFacet(NUXEO_DRIVE_FACET)) {
            rootContainer.addFacet(NUXEO_DRIVE_FACET);
        }
        String[] subscribers = (String[]) rootContainer.getPropertyValue(DRIVE_SUBSCRIBERS_PROPERTY);
        if (subscribers == null) {
            subscribers = new String[0];
        } else {
            if (Arrays.binarySearch(subscribers, userName) >= 0) {
                String[] old = subscribers;
                subscribers = new String[old.length - 1];
                for (int i = 0; i < old.length; i++) {
                    if (!userName.equals(old[i])) {
                        subscribers[i] = old[i];
                    }
                }
            }
        }
        rootContainer.setPropertyValue(DRIVE_SUBSCRIBERS_PROPERTY,
                (Serializable) subscribers);
        session.saveDocument(rootContainer);
        session.save();
        cache.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<IdRef> getSynchronizationRootReferences(boolean allRepositories,
            String userName, CoreSession session) throws ClientException {
        Map<String, Serializable[]> syncRoots = getSynchronizationRoots(
                allRepositories, userName, session);
        if (allRepositories) {
            Set<IdRef> syncRootRefs = new LinkedHashSet<IdRef>();
            for (Serializable[] repoSyncRoots : syncRoots.values()) {
                syncRootRefs.addAll((Set<IdRef>) repoSyncRoots[0]);
            }
            return syncRootRefs;
        } else {
            return (Set<IdRef>) syncRoots.get(session.getRepositoryName())[0];
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getSynchronizationRootPaths(boolean allRepositories,
            String userName, CoreSession session) throws ClientException {
        Map<String, Serializable[]> syncRoots = getSynchronizationRoots(
                allRepositories, userName, session);
        if (allRepositories) {
            Set<String> syncRootPaths = new LinkedHashSet<String>();
            for (Serializable[] repoSyncRoots : syncRoots.values()) {
                syncRootPaths.addAll((Set<String>) repoSyncRoots[1]);
            }
            return syncRootPaths;
        } else {
            return (Set<String>) syncRoots.get(session.getRepositoryName())[1];
        }
    }

    @Override
    public void handleFolderDeletion(IdRef deleted) throws ClientException {
        cache.clear();
    }

    /**
     * Uses the {@link AuditDocumentChangeFinder} to get the summary of document
     * changes for the given user and last successful synchronization date.
     * <p>
     * Sets the status code to
     * {@link DocumentChangeSummary#STATUS_TOO_MANY_CHANGES} if the audit log
     * query returns too many results, to
     * {@link DocumentChangeSummary#STATUS_NO_CHANGES} if no results are
     * returned and to {@link DocumentChangeSummary#STATUS_FOUND_CHANGES}
     * otherwise.
     * <p>
     * The {@link #DOCUMENT_CHANGE_LIMIT_PROPERTY} Framework property is used as
     * a limit of document changes to fetch from the audit logs. Default value
     * is 1000.
     */
    @Override
    public DocumentChangeSummary getDocumentChangeSummary(
            boolean allRepositories, String userName, CoreSession session,
            long lastSuccessfulSync) throws ClientException {

        // Get sync root paths
        Set<String> syncRootPaths = getSynchronizationRootPaths(
                allRepositories, userName, session);
        return getDocumentChangeSummary(allRepositories, syncRootPaths,
                session, lastSuccessfulSync);
    }

    /**
     * Uses the {@link AuditDocumentChangeFinder} to get the summary of document
     * changes for the given folder and last successful synchronization date.
     *
     * @see #getDocumentChangeSummary(boolean, String, CoreSession, long)
     */
    @Override
    public DocumentChangeSummary getFolderDocumentChangeSummary(
            String folderPath, CoreSession session, long lastSuccessfulSync)
            throws ClientException {

        Set<String> syncRootPaths = new HashSet<String>();
        syncRootPaths.add(folderPath);
        return getDocumentChangeSummary(false, syncRootPaths, session,
                lastSuccessfulSync);
    }

    protected DocumentChangeSummary getDocumentChangeSummary(
            boolean allRepositories, Set<String> syncRootPaths,
            CoreSession session, long lastSuccessfulSync)
            throws ClientException {

        List<DocumentChange> docChanges = new ArrayList<DocumentChange>();
        Map<String, DocumentModel> changedDocModels = new HashMap<String, DocumentModel>();
        String statusCode = DocumentChangeSummary.STATUS_NO_CHANGES;

        // Update sync date, rounded to the lower second to ensure consistency
        // in the case of databases that don't support milliseconds
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.MILLISECOND, 0);
        long syncDate = cal.getTimeInMillis();
        if (!syncRootPaths.isEmpty()) {
            try {
                // Get document changes
                int limit = Integer.parseInt(Framework.getProperty(
                        DOCUMENT_CHANGE_LIMIT_PROPERTY, "1000"));
                docChanges = documentChangeFinder.getDocumentChanges(
                        allRepositories, session, syncRootPaths,
                        lastSuccessfulSync, syncDate, limit);
                if (!docChanges.isEmpty()) {
                    // Fill map of document models that have changed and filter
                    // document changes to remove the one referring to documents
                    // not adaptable as a FileSystemItem, yet not synchronizable
                    addChangedDocModelsAndFilterDocChanges(allRepositories,
                            session, docChanges, changedDocModels);
                    if (!docChanges.isEmpty()) {
                        statusCode = DocumentChangeSummary.STATUS_FOUND_CHANGES;
                    }
                }
            } catch (TooManyDocumentChangesException e) {
                statusCode = DocumentChangeSummary.STATUS_TOO_MANY_CHANGES;
            }
        }

        return new DocumentChangeSummary(syncRootPaths, docChanges,
                changedDocModels, statusCode, syncDate);
    }

    protected Map<String, Serializable[]> getSynchronizationRoots(
            boolean allRepositories, String userName, CoreSession session)
            throws ClientException {
        // cache uses soft keys hence physical equality: intern key before
        // lookup
        userName = userName.intern();
        Map<String, Serializable[]> syncRoots = cache.get(userName);
        if (syncRoots == null) {
            syncRoots = new HashMap<String, Serializable[]>();
            String query = String.format(
                    "SELECT ecm:uuid FROM Document WHERE %s = %s"
                            + " AND ecm:currentLifeCycleState <> 'deleted'"
                            + " ORDER BY dc:title, dc:created DESC",
                    DRIVE_SUBSCRIBERS_PROPERTY,
                    NXQLQueryBuilder.prepareStringLiteral(userName, true, true));
            computeSynchronizationRoots(allRepositories, query, session,
                    syncRoots);
            cache.put(userName, syncRoots);
        }
        return syncRoots;
    }

    protected void computeSynchronizationRoots(boolean allRepositories,
            String query, CoreSession session,
            Map<String, Serializable[]> syncRoots) throws ClientException {

        if (allRepositories) {
            CoreSession repoSession = null;
            NuxeoPrincipal principal = (NuxeoPrincipal) session.getPrincipal();
            RepositoryManager repositoryManager = Framework.getLocalService(RepositoryManager.class);
            for (Repository repo : repositoryManager.getRepositories()) {
                Map<String, Serializable> context = new HashMap<String, Serializable>();
                context.put("principal", principal);
                try {
                    repoSession = repo.open(context);
                    queryAndFecthSynchronizationRoots(repoSession, query,
                            syncRoots);
                } catch (Exception e) {
                    throw ClientException.wrap(e);
                } finally {
                    if (repoSession != null) {
                        CoreInstance.getInstance().close(repoSession);
                    }
                }
            }
        } else {
            queryAndFecthSynchronizationRoots(session, query, syncRoots);
        }
    }

    protected void queryAndFecthSynchronizationRoots(CoreSession session,
            String query, Map<String, Serializable[]> syncRoots)
            throws ClientException {

        Serializable[] repoSyncRoots = new Serializable[2];
        Set<IdRef> references = new LinkedHashSet<IdRef>();
        Set<String> paths = new LinkedHashSet<String>();
        IterableQueryResult results = session.queryAndFetch(query, NXQL.NXQL);
        for (Map<String, Serializable> result : results) {
            IdRef docRef = new IdRef(result.get("ecm:uuid").toString());
            references.add(docRef);
            paths.add(session.getDocument(docRef).getPathAsString());
        }
        results.close();
        repoSyncRoots[0] = (Serializable) references;
        repoSyncRoots[1] = (Serializable) paths;
        syncRoots.put(session.getRepositoryName(), repoSyncRoots);
    }

    protected void addChangedDocModelsAndFilterDocChanges(
            boolean allRepositories, CoreSession session,
            List<DocumentChange> docChanges,
            Map<String, DocumentModel> changedDocModels) throws ClientException {

        if (allRepositories) {
            RepositoryManager repositoryManager = Framework.getLocalService(RepositoryManager.class);
            NuxeoPrincipal principal = (NuxeoPrincipal) session.getPrincipal();
            Map<String, CoreSession> repoSessions = new HashMap<String, CoreSession>();
            try {
                Iterator<DocumentChange> docChangesIt = docChanges.iterator();
                while (docChangesIt.hasNext()) {
                    DocumentChange docChange = docChangesIt.next();
                    String docUuid = docChange.getDocUuid();
                    if (!changedDocModels.containsKey(docUuid)) {
                        String repositoryId = docChange.getRepositoryId();
                        CoreSession repoSession = repoSessions.get(repositoryId);
                        if (repoSession == null) {
                            Repository repository = repositoryManager.getRepository(repositoryId);
                            if (repository == null) {
                                throw new ClientRuntimeException(
                                        String.format(
                                                "No repository registered with id '%s'.",
                                                repositoryId));
                            }
                            Map<String, Serializable> context = new HashMap<String, Serializable>();
                            context.put("principal", principal);
                            try {
                                repoSession = repository.open(context);
                            } catch (Exception e) {
                                throw ClientException.wrap(e);
                            }
                            repoSessions.put(repositoryId, repoSession);
                        }
                        addChangedDocModelAndFilterDocChange(repoSession,
                                docUuid, changedDocModels, docChangesIt);
                    }
                }
            } finally {
                for (CoreSession repoSession : repoSessions.values()) {
                    CoreInstance.getInstance().close(repoSession);
                }
            }
        } else {
            Iterator<DocumentChange> docChangesIt = docChanges.iterator();
            while (docChangesIt.hasNext()) {
                DocumentChange docChange = docChangesIt.next();
                String docUuid = docChange.getDocUuid();
                if (!changedDocModels.containsKey(docUuid)) {
                    addChangedDocModelAndFilterDocChange(session, docUuid,
                            changedDocModels, docChangesIt);
                }
            }
        }
    }

    /**
     * Adds the doc with the given uuid to the map of changed doc models if it
     * is adaptable as a FileSystemItem, ie. synchronizable, else removes the
     * doc change from the list of doc changes.
     */
    protected void addChangedDocModelAndFilterDocChange(CoreSession session,
            String docUuid, Map<String, DocumentModel> changedDocModels,
            Iterator<DocumentChange> docChangesIt) throws ClientException {

        DocumentModel doc = session.getDocument(new IdRef(docUuid));
        if (doc.getAdapter(FileSystemItem.class) == null) {
            docChangesIt.remove();
        } else {
            changedDocModels.put(docUuid, doc);
        }
    }

    // TODO: make documentChangeFinder overridable with an extension point and
    // remove setter
    public void setDocumentChangeFinder(
            DocumentChangeFinder documentChangeFinder) {
        this.documentChangeFinder = documentChangeFinder;
    }

}
