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
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.hierarchy.permission.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.drive.adapter.FolderItem;
import org.nuxeo.drive.adapter.impl.DocumentBackedFolderItem;
import org.nuxeo.drive.service.NuxeoDriveManager;
import org.nuxeo.drive.service.SynchronizationRoots;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.runtime.api.Framework;

/**
 * User workspace based implementation of the parent {@link FolderItem} of the user's synchronization roots.
 *
 * @author Antoine Taillefer
 */
public class UserSyncRootParentFolderItem extends DocumentBackedFolderItem {

    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(UserSyncRootParentFolderItem.class);

    protected boolean isUserWorkspaceSyncRoot = false;

    public UserSyncRootParentFolderItem(String factoryName, DocumentModel doc, FolderItem parentItem, String folderName)
            throws ClientException {
        this(factoryName, doc, parentItem, folderName, false);
    }

    public UserSyncRootParentFolderItem(String factoryName, DocumentModel doc, FolderItem parentItem,
            String folderName, boolean relaxSyncRootConstraint) throws ClientException {
        super(factoryName, parentItem, doc, relaxSyncRootConstraint);
        name = folderName;
        canRename = false;
        canDelete = false;
        isUserWorkspaceSyncRoot = isUserWorkspaceSyncRoot(doc);
        canCreateChild = isUserWorkspaceSyncRoot;
    }

    protected UserSyncRootParentFolderItem() {
        // Needed for JSON deserialization
    }

    @Override
    public void rename(String name) throws ClientException {
        throw new UnsupportedOperationException("Cannot rename a virtual folder item.");
    }

    @Override
    public void delete() throws ClientException {
        throw new UnsupportedOperationException("Cannot delete a virtual folder item.");
    }

    @Override
    public FileSystemItem move(FolderItem dest) throws ClientException {
        throw new UnsupportedOperationException("Cannot move a virtual folder item.");
    }

    @Override
    public List<FileSystemItem> getChildren() throws ClientException {

        if (isUserWorkspaceSyncRoot) {
            return super.getChildren();
        } else {
            List<FileSystemItem> children = new ArrayList<FileSystemItem>();
            Map<String, SynchronizationRoots> syncRootsByRepo = Framework.getLocalService(NuxeoDriveManager.class).getSynchronizationRoots(
                    principal);
            for (String repositoryName : syncRootsByRepo.keySet()) {
                try (CoreSession session = CoreInstance.openCoreSession(repositoryName, principal)) {
                    Set<IdRef> syncRootRefs = syncRootsByRepo.get(repositoryName).getRefs();
                    Iterator<IdRef> syncRootRefsIt = syncRootRefs.iterator();
                    while (syncRootRefsIt.hasNext()) {
                        IdRef idRef = syncRootRefsIt.next();
                        // TODO: ensure sync roots cache is up-to-date if ACL
                        // change, for now need to check permission
                        // See https://jira.nuxeo.com/browse/NXP-11146
                        if (!session.hasPermission(idRef, SecurityConstants.READ)) {
                            if (log.isDebugEnabled()) {
                                log.debug(String.format(
                                        "User %s has no READ access on synchronization root %s, not including it in children.",
                                        session.getPrincipal().getName(), idRef));
                            }
                            continue;
                        }
                        DocumentModel doc = session.getDocument(idRef);
                        // Filter by creator
                        // TODO: allow filtering by dc:creator in
                        // NuxeoDriveManager#getSynchronizationRoots(Principal
                        // principal)
                        if (session.getPrincipal().getName().equals(doc.getPropertyValue("dc:creator"))) {
                            FileSystemItem child = getFileSystemItemAdapterService().getFileSystemItem(doc, this);
                            if (child == null) {
                                if (log.isDebugEnabled()) {
                                    log.debug(String.format(
                                            "Synchronization root %s cannot be adapted as a FileSystemItem, maybe because user %s doesn't have the required permission on it (default required permission is ReadWrite). Not including it in children.",
                                            idRef, session.getPrincipal().getName()));
                                }
                                continue;
                            }
                            if (log.isDebugEnabled()) {
                                log.debug(String.format("Including synchronization root %s in children.", idRef));
                            }
                            children.add(child);
                        }
                    }
                }
            }
            Collections.sort(children);
            return children;
        }
    }

    private boolean isUserWorkspaceSyncRoot(DocumentModel doc) throws ClientException {
        NuxeoDriveManager nuxeoDriveManager = Framework.getLocalService(NuxeoDriveManager.class);
        return nuxeoDriveManager.isSynchronizationRoot(principal, doc);
    }

}
