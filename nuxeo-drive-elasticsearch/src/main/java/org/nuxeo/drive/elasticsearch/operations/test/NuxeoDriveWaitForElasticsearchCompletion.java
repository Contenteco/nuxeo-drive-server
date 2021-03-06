/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */
package org.nuxeo.drive.elasticsearch.operations.test;

import org.nuxeo.drive.operations.test.NuxeoDriveIntegrationTestsHelper;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.audit.ESAuditBackend;
import org.nuxeo.runtime.api.Framework;

/**
 * Waits for Elasticsearch audit completion.
 *
 * @since 7.3
 */
@Operation(id = NuxeoDriveWaitForElasticsearchCompletion.ID, category = Constants.CAT_SERVICES, label = "Nuxeo Drive: Wait for Elasticsearch audit completion")
public class NuxeoDriveWaitForElasticsearchCompletion {

    public static final String ID = "NuxeoDrive.WaitForElasticsearchCompletion";

    @OperationMethod
    public void run() {
        NuxeoDriveIntegrationTestsHelper.checkOperationAllowed();
        ElasticSearchAdmin esa = Framework.getService(ElasticSearchAdmin.class);
        esa.getClient().admin().indices().prepareFlush(ESAuditBackend.IDX_NAME).execute().actionGet();
        esa.getClient().admin().indices().prepareRefresh(ESAuditBackend.IDX_NAME).execute().actionGet();
    }

}
