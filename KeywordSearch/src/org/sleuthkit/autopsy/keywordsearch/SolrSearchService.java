/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.keywordsearch;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.MissingResourceException;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;
import org.openide.util.lookup.ServiceProviders;
import org.sleuthkit.autopsy.core.RuntimeProperties;
import org.sleuthkit.autopsy.framework.AutopsyService;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.framework.ProgressIndicator;
import org.sleuthkit.autopsy.keywordsearchservice.KeywordSearchService;
import org.sleuthkit.autopsy.keywordsearchservice.KeywordSearchServiceException;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * An implementation of the KeywordSearchService interface that uses Solr for
 * text indexing and search.
 */
@ServiceProviders(value = {
    @ServiceProvider(service = KeywordSearchService.class),
    @ServiceProvider(service = AutopsyService.class)}
)
public class SolrSearchService implements KeywordSearchService, AutopsyService {

    private static final Logger logger = Logger.getLogger(IndexFinder.class.getName());
    private static final String BAD_IP_ADDRESS_FORMAT = "ioexception occurred when talking to server"; //NON-NLS
    private static final String SERVER_REFUSED_CONNECTION = "server refused connection"; //NON-NLS
    private static final int IS_REACHABLE_TIMEOUT_MS = 1000;

    ArtifactTextExtractor extractor = new ArtifactTextExtractor();

    /**
     * Adds an artifact to the keyword search text index as a concantenation of
     * all of its attributes.
     *
     * @param artifact The artifact to index.
     *
     * @throws org.sleuthkit.datamodel.TskCoreException
     */
    @Override
    public void indexArtifact(BlackboardArtifact artifact) throws TskCoreException {
        if (artifact == null) {
            return;
        }

        // We only support artifact indexing for Autopsy versions that use
        // the negative range for artifact ids.
        if (artifact.getArtifactID() > 0) {
            return;
        }
        final Ingester ingester = Ingester.getDefault();

        try {
            ingester.indexMetaDataOnly(artifact);
            ingester.indexText(extractor, artifact, null);
        } catch (Ingester.IngesterException ex) {
            throw new TskCoreException(ex.getCause().getMessage(), ex);
        }
    }

    /**
     * Tries to connect to the keyword search service.
     *
     * @param host The hostname or IP address of the service.
     * @param port The port used by the service.
     *
     * @throws KeywordSearchServiceException if cannot connect.
     */
    @Override
    public void tryConnect(String host, int port) throws KeywordSearchServiceException {
        HttpSolrClient solrServer = null;
        if (host == null || host.isEmpty()) {
            throw new KeywordSearchServiceException(NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.MissingHostname")); //NON-NLS
        }
        try {
            solrServer = new HttpSolrClient.Builder("http://" + host + ":" + Integer.toString(port) + "/solr").build(); //NON-NLS
            KeywordSearch.getServer().connectToSolrServer(solrServer);
        } catch (SolrServerException ex) {
            throw new KeywordSearchServiceException(NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.HostnameOrPort")); //NON-NLS
        } catch (IOException ex) {
            String result = NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.HostnameOrPort"); //NON-NLS
            String message = ex.getCause().getMessage().toLowerCase();
            if (message.startsWith(SERVER_REFUSED_CONNECTION)) {
                try {
                    if (InetAddress.getByName(host).isReachable(IS_REACHABLE_TIMEOUT_MS)) {
                        // if we can reach the host, then it's probably port problem
                        result = Bundle.SolrConnectionCheck_Port();
                    } else {
                        result = NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.HostnameOrPort"); //NON-NLS
                    }
                } catch (IOException | MissingResourceException any) {
                    // it may be anything
                    result = NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.HostnameOrPort"); //NON-NLS
                }
            } else if (message.startsWith(BAD_IP_ADDRESS_FORMAT)) {
                result = NbBundle.getMessage(SolrSearchService.class, "SolrConnectionCheck.Hostname"); //NON-NLS
            }
            throw new KeywordSearchServiceException(result);
        } catch (NumberFormatException ex) {
            throw new KeywordSearchServiceException(Bundle.SolrConnectionCheck_Port());
        } catch (IllegalArgumentException ex) {
            throw new KeywordSearchServiceException(ex.getMessage());
        } finally {
            if (null != solrServer) {
                try {
                    solrServer.close();
                } catch (IOException ex) {
                    throw new KeywordSearchServiceException(ex.getMessage());
                }
            }
        }
    }

    /**
     * Deletes Solr core for a case.
     *
     * @param coreName The core name.
     */
    @Override
    public void deleteTextIndex(String coreName) throws KeywordSearchServiceException {
        KeywordSearch.getServer().deleteCore(coreName); 
    }
        
    @Override
    public void close() throws IOException {
    }

    /**
     * Checks whether user has requested to cancel Solr core open/create/upgrade
     * process. Throws exception if cancellation was requested.
     *
     * @param context CaseContext object
     *
     * @throws
     * org.sleuthkit.autopsy.framework.AutopsyService.AutopsyServiceException
     */
    static void checkCancellation(CaseContext context) throws AutopsyServiceException {
        if (context.cancelRequested()) {
            throw new AutopsyServiceException("Cancellation requested by user");
        }
    }

    /**
     *
     * @param context
     *
     * @throws
     * org.sleuthkit.autopsy.framework.AutopsyService.AutopsyServiceException
     */
    @Override
    @NbBundle.Messages({
        "SolrSearch.findingIndexes.msg=Looking for existing text index directories",
        "SolrSearch.creatingNewIndex.msg=Creating new text index",
        "SolrSearch.indentifyingIndex.msg=Identifying text index for upgrade",
        "SolrSearch.openCore.msg=Creating/Opening text index",
        "SolrSearch.complete.msg=Text index successfully opened"})
    public void openCaseResources(CaseContext context) throws AutopsyServiceException {
        /*
         * Autopsy service providers may not have case-level resources.
         */

        ProgressIndicator progress = context.getProgressIndicator();
        int totalNumProgressUnits = 8;
        int progressUnitsCompleted = 1;

        // do a case subdirectory search to check for the existence and upgrade status of KWS indexes
        progress.start(Bundle.SolrSearch_findingIndexes_msg(), totalNumProgressUnits);
        IndexFinder indexFinder = new IndexFinder();
        List<Index> indexes = indexFinder.findAllIndexDirs(context.getCase());

        // Check for cancellation at whatever points are feasible
        checkCancellation(context);

        // check if index needs upgrade
        Index currentVersionIndex;
        if (indexes.isEmpty()) {
            // new case that doesn't have an existing index. create new index folder
            progress.progress(Bundle.SolrSearch_creatingNewIndex_msg(), progressUnitsCompleted++);
            currentVersionIndex = IndexFinder.createLatestVersionIndexDir(context.getCase());
            currentVersionIndex.setNewIndex(true);
        } else {
            // check if one of the existing indexes is for latest Solr version and schema
            progress.progress(Bundle.SolrSearch_indentifyingIndex_msg(), progressUnitsCompleted++);
            currentVersionIndex = IndexFinder.findLatestVersionIndexDir(indexes);
            if (currentVersionIndex == null) {
                // found existing index(es) but none were for latest Solr version and schema version
                Index indexToUpgrade = IndexFinder.identifyIndexToUpgrade(indexes);
                if (indexToUpgrade == null) {
                    // unable to find index that can be upgraded
                    throw new AutopsyServiceException("Unable to find index that can be upgraded to the latest version of Solr");
                }

                // Check for cancellation at whatever points are feasible
                checkCancellation(context);

                double currentSolrVersion = NumberUtils.toDouble(IndexFinder.getCurrentSolrVersion());
                double indexSolrVersion = NumberUtils.toDouble(indexToUpgrade.getSolrVersion());
                if (indexSolrVersion > currentSolrVersion) {
                    // oops!
                    throw new AutopsyServiceException("Unable to find index to use for Case open");
                } else if (indexSolrVersion == currentSolrVersion) {
                    // latest Solr version but not latest schema. index should be used in read-only mode and not be upgraded.
                    if (RuntimeProperties.runningWithGUI()) {
                        // pop up a message box to indicate the read-only restrictions.
                        if (!KeywordSearchUtil.displayConfirmDialog(NbBundle.getMessage(this.getClass(), "SolrSearchService.IndexReadOnlyDialog.title"),
                                NbBundle.getMessage(this.getClass(), "SolrSearchService.IndexReadOnlyDialog.msg"),
                                KeywordSearchUtil.DIALOG_MESSAGE_TYPE.WARN)) {
                            // case open declined - throw exception
                            throw new AutopsyServiceException("Case open declined by user");
                        }
                    }
                    // proceed with case open
                    currentVersionIndex = indexToUpgrade;
                } else {
                    // index needs to be upgraded to latest supported version of Solr
                    if (RuntimeProperties.runningWithGUI()) {
                        //pop up a message box to indicate the restrictions on adding additional 
                        //text and performing regex searches and give the user the option to decline the upgrade
                        if (!KeywordSearchUtil.displayConfirmDialog(NbBundle.getMessage(this.getClass(), "SolrSearchService.IndexUpgradeDialog.title"),
                                NbBundle.getMessage(this.getClass(), "SolrSearchService.IndexUpgradeDialog.msg"),
                                KeywordSearchUtil.DIALOG_MESSAGE_TYPE.WARN)) {
                            // upgrade declined - throw exception
                            throw new AutopsyServiceException("Index upgrade was declined by user");
                        }
                    }

                    // Copy the existing index and config set into ModuleOutput/keywordsearch/data/solrX_schema_Y/
                    String newIndexDir = indexFinder.copyIndexAndConfigSet(indexToUpgrade, context, progressUnitsCompleted);
                    progressUnitsCompleted += 2; // add progress increments for copying existing index and config set

                    // upgrade the existing index to the latest supported Solr version
                    IndexUpgrader indexUpgrader = new IndexUpgrader();
                    currentVersionIndex = indexUpgrader.performIndexUpgrade(newIndexDir, indexToUpgrade, context, progressUnitsCompleted);
                    if (currentVersionIndex == null) {
                        throw new AutopsyServiceException("Unable to upgrade index to the latest version of Solr");
                    }
                }
            }
        }

        // Check for cancellation at whatever points are feasible
        checkCancellation(context);
        
        // open core
        try {
            progress.progress(Bundle.SolrSearch_openCore_msg(), totalNumProgressUnits - 1);
            KeywordSearch.getServer().openCoreForCase(context.getCase(), currentVersionIndex);
        } catch (Exception ex) {
            throw new AutopsyServiceException(String.format("Failed to open or create core for %s", context.getCase().getCaseDirectory()), ex);
        }
        
        progress.progress(Bundle.SolrSearch_complete_msg(), totalNumProgressUnits);
    }

    /**
     *
     * @param context
     * @throws org.sleuthkit.autopsy.framework.AutopsyService.AutopsyServiceException
     */
    @Override
    public void closeCaseResources(CaseContext context) throws AutopsyServiceException {
        /*
         * Autopsy service providers may not have case-level resources.
         */
        try {
            KeywordSearchResultFactory.BlackboardResultWriter.stopAllWriters();
            /*
             * TODO (AUT-2084): The following code
             * KeywordSearch.CaseChangeListener gambles that any
             * BlackboardResultWriters (SwingWorkers) will complete in less than
             * roughly two seconds
             */
            Thread.sleep(2000);
            KeywordSearch.getServer().closeCore();
        } catch (Exception ex) {
            throw new AutopsyServiceException(String.format("Failed to close core for %s", context.getCase().getCaseDirectory()), ex);
        }
    }

    @Override
    public String getServiceName() {
        return NbBundle.getMessage(this.getClass(), "SolrSearchService.ServiceName");
    }
}
