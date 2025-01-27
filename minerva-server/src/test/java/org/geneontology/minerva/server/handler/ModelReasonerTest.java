package org.geneontology.minerva.server.handler;

import org.geneontology.minerva.ModelContainer;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.JsonOwlIndividual;
import org.geneontology.minerva.lookup.ExternalLookupService;
import org.geneontology.minerva.server.handler.M3BatchHandler.*;
import org.geneontology.minerva.server.inferences.CachingInferenceProviderCreatorImpl;
import org.geneontology.minerva.server.inferences.InferenceProviderCreator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ModelReasonerTest {

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    private static CurieHandler curieHandler = null;
    private static JsonOrJsonpBatchHandler handler = null;
    private static UndoAwareMolecularModelManager models = null;
    static final String go_lego_journal_file = "/tmp/test-go-lego-blazegraph.jnl";

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        init();
    }

    static void init() throws OWLOntologyCreationException, IOException {
        //FIXME need more from go-lego
        final OWLOntology tbox = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(new File("src/test/resources/go-lego-minimal.owl")));
        // curie handler
        final String modelIdcurie = "gomodel";
        final String modelIdPrefix = "http://model.geneontology.org/";
        final CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
        curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);

        models = new UndoAwareMolecularModelManager(tbox, curieHandler, modelIdPrefix, folder.newFile().getAbsolutePath(), null, go_lego_journal_file, true);
        InferenceProviderCreator ipc = CachingInferenceProviderCreatorImpl.createElk(false, null);
        handler = new JsonOrJsonpBatchHandler(models, "development", ipc,
                Collections.<OWLObjectProperty>emptySet(), (ExternalLookupService) null);
        //models.setPathToOWLFiles("src/test/resources/reasoner-test");
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        if (handler != null) {
            handler = null;
        }
        if (models != null) {
            models.dispose();
        }
    }

    //FIXME @Test
    public void testReasoner() throws Exception {
        List<M3Request> batch = new ArrayList<>();
        M3Request r;

        final String individualId = "http://model.geneontology.org/5525a0fc00000001/5525a0fc0000023";
        final IRI individualIRI = IRI.create(individualId);
        final String individualIdCurie = curieHandler.getCuri(individualIRI);
        final String modelId = "http://model.geneontology.org/5525a0fc00000001";
        final ModelContainer model = models.getModel(IRI.create(modelId));
        assertNotNull(model);
        boolean found = false;
        boolean foundCurie = false;
        Set<OWLNamedIndividual> individuals = model.getAboxOntology().getIndividualsInSignature();
        for (OWLNamedIndividual individual : individuals) {
            if (individualIRI.equals(individual.getIRI())) {
                found = true;
                foundCurie = individualIdCurie.equals(curieHandler.getCuri(individual.getIRI()));
            }
        }
        assertTrue(found);
        assertTrue(foundCurie);


        // get model
        r = new M3Request();
        r.entity = Entity.model;
        r.operation = Operation.get;
        r.arguments = new M3Argument();
        r.arguments.modelId = modelId;
        batch.add(r);

        M3BatchResponse response = executeBatch(batch);
        JsonOwlIndividual[] responseIndividuals = BatchTestTools.responseIndividuals(response);
        JsonOwlIndividual targetIndividual = null;
        for (JsonOwlIndividual individual : responseIndividuals) {
            if (individualIdCurie.equals(individual.id)) {
                targetIndividual = individual;
                break;
            }
        }
        assertNotNull(targetIndividual);
        assertNotNull(targetIndividual.inferredType);
        assertEquals("Expected two inferences", 2, targetIndividual.inferredType.length);
    }

    private M3BatchResponse executeBatch(List<M3Request> batch) {
        M3BatchResponse response = handler.m3Batch("test-user", Collections.emptySet(), "test-intention", "foo-packet-id",
                batch.toArray(new M3Request[batch.size()]), true, true);
        assertEquals("test-user", response.uid);
        assertEquals("test-intention", response.intention);
        assertEquals(response.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response.messageType);
        return response;
    }
}
