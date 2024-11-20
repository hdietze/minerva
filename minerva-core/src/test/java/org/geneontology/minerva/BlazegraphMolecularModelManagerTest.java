package org.geneontology.minerva;

import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.SPARQLResultJSONRenderer;
import org.geneontology.minerva.util.AnnotationShorthand;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.QueryResult;
import org.openrdf.query.TupleQueryResult;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.geneontology.minerva.BlazegraphOntologyManager.in_taxon;
import static org.junit.Assert.*;

public class BlazegraphMolecularModelManagerTest {
    private final CurieHandler curieHandler = DefaultCurieHandler.getDefaultHandler();
    static final String go_lego_journal_file = "/tmp/test-go-lego-blazegraph.jnl";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * Test whether the revised import function properly digests turtle files. We import a ttl file
     * into the BlazegraphMolecularModel, dump the model into files, and then compare these files.
     * Check this pull request for more information: https://github.com/geneontology/minerva/pull/144/files
     *
     * @throws Exception
     */
    @Test
    public void testImportDump() throws Exception {
        /* I used the file from one of the turtle file in https://github.com/geneontology/noctua-models/blob/master/models/0000000300000001.ttl */
        String sourceModelPath = "src/test/resources/dummy-noctua-model.ttl";
        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();

        /* Import the test turtle file */
        String modelId = m3.importModelToDatabase(new File(sourceModelPath), false);
        /* Dump back triples in the model to temporary files */
        m3.dumpStoredModel(IRI.create(modelId), folder.getRoot());
        File dumpedModel = folder.getRoot().toPath().resolve(modelId.toString().replace("http://model.geneontology.org/", "") + ".ttl").toFile();
        compareDumpUsingJena(new File(sourceModelPath), dumpedModel, null);
        m3.dispose();
    }

    @Test
    public void testTaxonAnnotationMaintenance() throws Exception {
        IRI human = IRI.create("http://purl.obolibrary.org/obo/NCBITaxon_9606");
        IRI boar = IRI.create("http://purl.obolibrary.org/obo/NCBITaxon_9823");
        String sourceModelPath = "src/test/resources/test-model-taxon-annotations.ttl";
        IRI modelIRI = IRI.create("http://model.geneontology.org/62183af000000536");
        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();
        m3.importModelToDatabase(new File(sourceModelPath), false);
        ModelContainer model = m3.getModel(modelIRI);
        Set<IRI> previousTaxonIRIs = model.getAboxOntology().getAnnotations().stream()
                .filter(a -> a.getProperty().equals(in_taxon))
                .map(a -> a.getValue().asIRI().get())
                .collect(Collectors.toSet());
        assertTrue(previousTaxonIRIs.contains(human));
        assertTrue(previousTaxonIRIs.contains(boar));
        m3.saveModel(model);
        Set<IRI> newTaxonIRIs = model.getAboxOntology().getAnnotations().stream()
                .filter(a -> a.getProperty().equals(in_taxon))
                .map(a -> a.getValue().asIRI().get())
                .collect(Collectors.toSet());
        assertTrue(newTaxonIRIs.contains(human));
        assertFalse(newTaxonIRIs.contains(boar));
    }

    @Test
    public void testRemoveImportsDuringImport() throws Exception {
        String sourceModelPath = "src/test/resources/dummy-noctua-modelwith-import.ttl";
        OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
        OWLOntology cam = ontman.loadOntologyFromOntologyDocument(new File(sourceModelPath));
        int axioms = cam.getAxiomCount();
        Set<OWLImportsDeclaration> imports = cam.getImportsDeclarations();
        assertFalse(imports.size() == 0);

        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();
        /* Import the test turtle file */
        String modelId = m3.importModelToDatabase(new File(sourceModelPath), false);
        // read it back out and show it is imports free
        OWLOntology loaded = m3.loadModelABox(IRI.create(modelId));
        Set<OWLImportsDeclaration> shouldbenone = loaded.getImportsDeclarations();
        int loadedaxioms = loaded.getAxiomCount();
        assertTrue(shouldbenone.size() == 0);
        assertTrue(axioms == loadedaxioms);
        m3.dispose();
    }

    /**
     * Test the whole cycle of data processing using Blazegraph.
     * Check this pull request: https://github.com/geneontology/minerva/issues/143
     *
     * @throws Exception
     */
    @Test
    public void testFullCycle() throws Exception {
        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();
        try {
            ModelContainer model = m3.generateBlankModel(null);
            testModelSaveLoad(m3, model);
            m3.unlinkModel(model.getModelId());
            assertEquals(m3.getModelIds().size(), 0);

            model = m3.generateBlankModel(null);
            testModelAddRemove(m3, model);
            testModelImport(m3, model);
        } finally {
            m3.dispose();
        }
    }

    @Test
    public void testModelStateDelete() throws Exception {
        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();
        try {
            final OWLDataFactory df = m3.getOntology().getOWLOntologyManager().getOWLDataFactory();
            final OWLObjectProperty partOf = df.getOWLObjectProperty(curieHandler.getIRI("BFO:0000050"));
            final OWLAnnotationProperty modelState = df.getOWLAnnotationProperty(AnnotationShorthand.modelstate.getAnnotationProperty());

            ModelContainer model1 = m3.generateBlankModel(null);
            OWLNamedIndividual i1 = m3.createIndividualWithIRI(model1, curieHandler.getIRI("GO:0000001"), null, null);
            OWLNamedIndividual i2 = m3.createIndividualWithIRI(model1, curieHandler.getIRI("GO:0000002"), null, null);
            m3.addFact(model1, partOf, i1, i2, Collections.<OWLAnnotation>emptySet(), null);

            ModelContainer model2 = m3.generateBlankModel(null);
            OWLNamedIndividual i3 = m3.createIndividualWithIRI(model2, curieHandler.getIRI("GO:0000001"), null, null);
            OWLNamedIndividual i4 = m3.createIndividualWithIRI(model2, curieHandler.getIRI("GO:0000002"), null, null);
            m3.addFact(model2, partOf, i3, i4, Collections.<OWLAnnotation>emptySet(), null);
            m3.addModelAnnotations(model2, Collections.singleton(df.getOWLAnnotation(modelState, df.getOWLLiteral("delete"))), null);
            m3.saveAllModels();

            File dir = folder.newFolder();
            m3.dumpStoredModel(model1.getModelId(), dir);
            m3.dumpStoredModel(model2.getModelId(), dir);
            m3.dispose();

            BlazegraphMolecularModelManager<Void> m3b = createBlazegraphMolecularModelManager();
            try {
                assertEquals(2, dir.list().length);
                for (File file : dir.listFiles()) {
                    m3b.importModelToDatabase(file, true);
                }
                assertEquals(1, m3b.getStoredModelIds().size());
            } finally {
                m3b.dispose();
            }
        } finally {
            m3.dispose();
        }
    }

    @Test
    public void testSPARQLQuery() throws Exception {
        String sourceModelPath = "src/test/resources/dummy-noctua-model.ttl";
        BlazegraphMolecularModelManager<Void> m3 = createBlazegraphMolecularModelManager();
        try {
            /* Import the test turtle file */
            m3.importModelToDatabase(new File(sourceModelPath), false);
            QueryResult selectResult = m3.executeSPARQLQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", 10);
            assertTrue(selectResult instanceof TupleQueryResult);
            assertEquals("http://model.geneontology.org/0000000300000001", ((TupleQueryResult) selectResult).next().getBinding("g").getValue().stringValue());
            QueryResult constructResult = m3.executeSPARQLQuery("CONSTRUCT { ?s <http://example.org/subject_in> ?g } WHERE { GRAPH ?g { ?s ?p ?o } }", 10);
            assertTrue(constructResult instanceof GraphQueryResult);
            assertEquals("http://model.geneontology.org/0000000300000001", ((GraphQueryResult) constructResult).next().getObject().stringValue());
            SPARQLResultJSONRenderer renderer = new SPARQLResultJSONRenderer(m3.getCuriHandler());
            String gValue = renderer.renderResults((TupleQueryResult) m3.executeSPARQLQuery("SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", 10))
                    .getAsJsonObject("results")
                    .getAsJsonArray("bindings").get(0)
                    .getAsJsonObject().getAsJsonObject("g").getAsJsonPrimitive("value").getAsString();
            assertEquals("gomodel:0000000300000001", gValue);
            JsonObject graphJson = renderer.renderGraph((GraphQueryResult) m3.executeSPARQLQuery("CONSTRUCT { ?s <http://example.org/subject_in> ?g } WHERE { GRAPH ?g { ?s ?p ?o } }", 10));
            String objectValue = graphJson.getAsJsonObject("GO:0000981").getAsJsonArray("ex:subject_in").get(0).getAsJsonObject().getAsJsonPrimitive("value").getAsString();
            assertEquals("gomodel:0000000300000001", objectValue);
        } finally {
            m3.dispose();
        }
    }

    /**
     * Test the process that adds some individuals, saves them and then loads them back into the model.
     *
     * @param m3
     * @param model
     * @throws Exception
     */
    private void testModelSaveLoad(BlazegraphMolecularModelManager<Void> m3, ModelContainer model) throws Exception {
        IRI modelID = model.getModelId();
        final OWLObjectProperty partOf = m3.getOntology().getOWLOntologyManager().
                getOWLDataFactory().getOWLObjectProperty(curieHandler.getIRI("BFO:0000050"));
        OWLNamedIndividual i1 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000001"), null, null);
        OWLNamedIndividual i2 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000002"), null, null);

        m3.addFact(model, partOf, i1, i2, Collections.<OWLAnnotation>emptySet(), null);
        m3.saveModel(model);
        m3.unlinkModel(modelID);

        /* getModel internally calls the loadModel method */
        model = m3.getModel(modelID);
        Collection<OWLNamedIndividual> loaded = m3.getIndividuals(model.getModelId());
        assertTrue(loaded.contains(i1));
        assertTrue(loaded.contains(i2));
    }

    /**
     * Repeatedly add and remove individuals/facts and check what happens.
     * Also check the case whether individuals can be added after the Blazegraph instance is shutdown.
     *
     * @param m3
     * @param model
     * @throws Exception
     */
    private void testModelAddRemove(BlazegraphMolecularModelManager<Void> m3, ModelContainer model) throws Exception {
        final OWLObjectProperty partOf = m3.getOntology().getOWLOntologyManager().getOWLDataFactory().getOWLObjectProperty(curieHandler.getIRI("BFO:0000050"));
        OWLNamedIndividual i1 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000001"), null, null);
        OWLNamedIndividual i2 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000002"), null, null);
        OWLNamedIndividual i3 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000003"), null, null);
        OWLNamedIndividual i4 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000004"), null, null);

        /* Add four individuals */
        m3.addFact(model, partOf, i1, i2, Collections.<OWLAnnotation>emptySet(), null);
        m3.addFact(model, partOf, i3, i4, Collections.<OWLAnnotation>emptySet(), null);
        m3.saveModel(model);
        Collection<OWLNamedIndividual> loaded = m3.getIndividuals(model.getModelId());
        assertTrue(loaded.contains(i1) && loaded.contains(i2) && loaded.contains(i3) && loaded.contains(i4));

        /* Remove the partOf triple that connects i1 and i2 */
        m3.removeFact(model, partOf, i1, i2, null);
        m3.saveModel(model);
        loaded = m3.getIndividuals(model.getModelId());
        assertTrue(loaded.contains(i1) && loaded.contains(i2) && loaded.contains(i3) && loaded.contains(i4));

        /* Remove the i1 and i2 */
        m3.deleteIndividual(model, i1, null);
        m3.deleteIndividual(model, i2, null);
        m3.saveModel(model);
        loaded = m3.getIndividuals(model.getModelId());
        assertTrue(!loaded.contains(i1) && !loaded.contains(i2) && loaded.contains(i3) && loaded.contains(i4));

        /* Trying to remove the fact that is already removed */
        m3.deleteIndividual(model, i1, null);
        m3.saveModel(model);
        loaded = m3.getIndividuals(model.getModelId());
        assertTrue(!loaded.contains(i1) && !loaded.contains(i2) && loaded.contains(i3) && loaded.contains(i4));

        /* Re-add the i1 */
        i1 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000001"),
                null, null);
        loaded = m3.getIndividuals(model.getModelId());
        assertTrue(loaded.contains(i1) && !loaded.contains(i2) && loaded.contains(i3) && loaded.contains(i4));
        m3.saveModel(model);
        assertEquals(m3.getModelIds().size(), 1);

        m3.unlinkModel(model.getModelId());

        /* i5 should not be added; createIndividualWithIRI should throw java.lang.IllegalStateException */
        try {
            OWLNamedIndividual i5 = m3.createIndividualWithIRI(model, curieHandler.getIRI("GO:0000005"), null, null);
            m3.saveModel(model);
            fail("Creating individual after disposing the model manager should not be allowed.");
        } catch (IllegalStateException e) {
        }
    }

    /**
     * Dump stored model and read back the dumped ttl files; check whether the model is properly reconstructed
     * from ttl files. Double-check whether the model is properly dumped using Jena.
     *
     * @param m3
     * @param model
     * @throws Exception
     */
    private void testModelImport(BlazegraphMolecularModelManager<Void> m3, ModelContainer model) throws Exception {
        IRI modelId = model.getModelId();
        /* Dump the specific model that match model's Id */
        m3.dumpStoredModel(modelId, folder.getRoot());
        /* So far we created and saved two models */
        assertEquals(m3.getAvailableModelIds().size(), 2);
        /* Shutdown the database instance */
        m3.dispose();

        /* Create the instance again */
        m3 = createBlazegraphMolecularModelManager();
        /* Import the dumped ttl files */
        String[] extensions = new String[]{"ttl"};
        List<File> files = (List<File>) FileUtils.listFiles(folder.getRoot(), extensions, true);
        for (File file : files)
            m3.importModelToDatabase(file, false);

        /* Check whether the model contains all individuals we created before */
        for (OWLNamedIndividual ind : m3.getIndividuals(modelId)) {
            IRI iri = ind.getIRI();
            assertTrue(iri.equals(curieHandler.getIRI("GO:0000001")) || iri.equals(curieHandler.getIRI("GO:0000003")) || iri.equals(curieHandler.getIRI("GO:0000004")));
            assertFalse(iri.equals(curieHandler.getIRI("GO:0000002")));
        }

        /* Compare the model constructed from dump files with the model constructed using pre-dumped files */
        File dumpedModel = folder.getRoot().toPath().resolve(modelId.toString().replace("http://model.geneontology.org/", "") + ".ttl").toFile();
        compareDumpUsingJena(new File("src/test/resources/mmg/basic-fullcycle-dump.ttl"), dumpedModel, modelId.toString());
        m3.dispose();
    }

    /**
     * @return the instance of BlazegraphMolecularModelManager
     * @throws Exception
     */
    private BlazegraphMolecularModelManager<Void> createBlazegraphMolecularModelManager() throws Exception {
        /* A path of the temporary journal file for Blazegraph storage system */
        String journalPath = folder.newFile().getAbsolutePath();
        /* A root path of the temporary directory */
        String tempRootPath = folder.getRoot().getAbsolutePath();
        /* Delete the journal file if exists */
        FileUtils.deleteQuietly(new File(journalPath));
        OWLOntology tbox = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(new File("src/test/resources/mmg/basic-tbox.omn")));
        Map<String, String> prefixes = new HashMap<>();
        prefixes.put("gomodel", "http://model.geneontology.org/");
        prefixes.put("ex", "http://example.org/");
        prefixes.put("GO", "http://purl.obolibrary.org/obo/GO_");
        CurieHandler curieHandler = new MappedCurieHandler(prefixes);
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(tbox, curieHandler, "http://model.geneontology.org/", journalPath, tempRootPath, go_lego_journal_file, true);
        return m3;
    }

    /**
     * Compare two sets of turtle files and check whether they are equivalent.
     * Dump files often have different orders of triples compared with the ones in the original file,
     * thus one-by-one comparison is obviously not working here. We therefore leverage Jena's model, i.e.,
     * import original file and dump files using Jena and then compare them using Jena's isIsomorphicWith function.
     *
     * @param sourceFile
     * @param targetFile
     * @param targetModelIdStr
     * @throws IOException
     */
    private void compareDumpUsingJena(File sourceFile, File targetFile, String targetModelIdStr) throws IOException {
        /* Read triples from a single source file */
        Model sourceModel = ModelFactory.createDefaultModel();
        sourceModel.read(new FileInputStream(sourceFile), null, "TURTLE");

        /* Read triples from a directory */
        String[] extensions = new String[]{"ttl"};
        Model targetModel = ModelFactory.createDefaultModel();
        extensions = new String[]{"ttl"};
        targetModel.read(targetFile.getCanonicalPath());

        /*
         * The modelId is randomly generated for every time we create a new model and the modelId
         * is also added as resources in dump files. Therefore, when we run this test code,
         * the same model with the different Id is generated every time, so Jena think these models
         * are different models due to the difference of the modelId (although other triples are equivalent).
         * We therefore remove triples containing modelId before we compare the models using isIsomorphicWith.
         */
        if (targetModelIdStr != null) {
            Resource modelIdRes = targetModel.createResource(targetModelIdStr);
            targetModel.removeAll(modelIdRes, null, null);
        }

        /* Does the dumped file contain all triples from the source file (and vice versa)? */
        if (sourceModel.isIsomorphicWith(targetModel) != true)
            fail("Source graphs and target graphs are not isomorphic.");
    }
}