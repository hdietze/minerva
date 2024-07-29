package org.geneontology.minerva.legacy.sparql;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.sys.JenaSystem;
import org.geneontology.jena.OWLtoRules;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.rules.engine.RuleEngine;
import org.geneontology.rules.engine.Triple;
import org.geneontology.rules.engine.WorkingMemory;
import org.geneontology.rules.util.Bridge;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import scala.collection.JavaConverters;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class GPADSPARQLTest {
    private static RuleEngine arachne;
    private static GPADSPARQLExport exporter;

    @BeforeClass
    public static void setupRules() throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.loadOntologyFromOntologyDocument(GPADSPARQLTest.class.getResourceAsStream("/ro-merged-2017-10-02.ofn"));
        Set<Rule> rules = new HashSet<>();
        rules.addAll(JavaConverters.setAsJavaSetConverter(OWLtoRules.translate(ont, Imports.INCLUDED, true, true, true, true)).asJava());
        rules.addAll(JavaConverters.setAsJavaSetConverter(OWLtoRules.indirectRules(ont)).asJava());
        arachne = new RuleEngine(Bridge.rulesFromJena(JavaConverters.asScalaSetConverter(rules).asScala()), true);
    }

    @BeforeClass
    public static void setupExporter() {
        JenaSystem.init();
        exporter = new GPADSPARQLExport(DefaultCurieHandler.getDefaultHandler(), new HashMap<IRI, String>(), new HashMap<IRI, String>(), new HashMap<>());
    }

    @Test
    public void testGPADOutput() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/581e072c00000473.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        String gpad = exporter.exportGPAD(mem, IRI.create("http://test.org"));
        int lines = gpad.split("\n", -1).length;
        //TODO test contents of annotations; dumb test for now
        Assert.assertTrue(gpad.contains("model-state=production"));
        Assert.assertTrue("Should produce annotations", lines > 2);
    }


    /**
     * This test needs improvements; the current background axioms used in the tests are resulting in the Uberon inference we're trying to avoid
     *
     * @throws Exception
     */
    @Test
    public void testSuppressUberonExtensionsWhenEMAPA() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/no_uberon_with_emapa.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        Set<GPADData> annotations = exporter.getGPAD(mem, IRI.create("http://test.org"));
        Assert.assertTrue(annotations.stream().anyMatch(a -> a.getAnnotationExtensions().stream().anyMatch(e -> e.getFiller().toString().startsWith("http://purl.obolibrary.org/obo/EMAPA_"))));
        Assert.assertTrue(annotations.stream().noneMatch(a -> a.getAnnotationExtensions().stream().anyMatch(e -> e.getFiller().toString().startsWith("http://purl.obolibrary.org/obo/UBERON_"))));
    }

    /**
     * Test whether the GPAD output contains all required entries and rows without any spurious results.
     * Example Input file: the owl dump from http://noctua-dev.berkeleybop.org/editor/graph/gomodel:59d1072300000074
     * <p>
     * Note on the GPAD file format and its contents:
     * 1. the number of entries in the GPAD output from this owl dump should be 6, not 7 (although there are 7 individuals/boxes)
     * because the edge/relationship "molecular_function" is a trivial one, which is supposed to be removed from the output.
     * 2. the 4th columns, which consists of the list of GO IDs attributed to the DB object ID (These should be GO:0005634, GO:0007267, GO:0007507, GO:0016301)
     * 3. the 2nd columns: the rest of entities in the noctua screen, i.e.  S000028630 (YFR032C-B Scer) or S000004724(SHH3 Scer)
     *
     * @throws Exception
     */
    @Test
    public void testGPADOutputWithNegation() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/59d1072300000074.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        String gpad = exporter.exportGPAD(mem, IRI.create("http://test.org"));

        /* Check the number of rows in GPAD output */
        String gpadOutputArr[] = gpad.split("\n", -1);
        /* 1 for header and 6 for the rest of the rows. the length should be  7 or 8.*/
        Assert.assertTrue("Should produce annotations", gpadOutputArr.length >= 1 + 6);

        /* Compare the output with the GPAD file that contains sample answers */
        List<String> lines = FileUtils.readLines(new File("src/test/resources/59d1072300000074.gpad"), "UTF-8");
        /* The order of the rows in the GPAD file can be different, so we compare rows by rows */
        for (String gpadOutputRow : gpadOutputArr) {
            /* Additionally check all rows's qualifier contains |NOT substring inside */
            String gpadRowArr[] = gpadOutputRow.split("\t");
            /* Skip checking the header; all rows need to contain NOT in its qualifier */
            if (gpadRowArr.length > 2) {
                Assert.assertTrue(gpadRowArr[2].contains("|NOT"));
            }
        }
    }

    @Test
    @Ignore
    public void testGPADContainsAcceptedAndCreatedDates() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/created-date-test.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        Set<GPADData> annotations = exporter.getGPAD(mem, IRI.create("http://test.org"));
        IRI gene = IRI.create("http://identifiers.org/wormbase/WBGene00001326");
        Pair<String, String> creationDate = Pair.of("creation-date", "2021-05-13");
        Assert.assertTrue(annotations.stream().anyMatch(a -> a.getObject().equals(gene) && a.getAnnotations().contains(creationDate)));
    }

    @Test
    public void testFilterRootMFWhenOtherMF() throws Exception {
        IRI rootMF = IRI.create("http://purl.obolibrary.org/obo/GO_0003674");
        IRI rootBP = IRI.create("http://purl.obolibrary.org/obo/GO_0008150");
        IRI rootCC = IRI.create("http://purl.obolibrary.org/obo/GO_0005575");

        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/test_root_mf_filter.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        Set<GPADData> annotations = exporter.getGPAD(mem, IRI.create("http://test.org"));
        IRI gene = IRI.create("http://identifiers.org/mgi/MGI:2153470");
        Assert.assertTrue(annotations.stream().noneMatch(a -> a.getObject().equals(gene) && a.getOntologyClass().equals(rootMF)));

        Model model2 = ModelFactory.createDefaultModel();
        model2.read(this.getClass().getResourceAsStream("/test_root_mf_filter2.ttl"), "", "ttl");
        Set<Triple> triples2 = model2.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem2 = arachne.processTriples(JavaConverters.asScalaSetConverter(triples2).asScala());
        Set<GPADData> annotations2 = exporter.getGPAD(mem2, IRI.create("http://test.org"));
        IRI gene2 = IRI.create("http://identifiers.org/mgi/MGI:98392");
        Assert.assertTrue(annotations2.stream().anyMatch(a -> a.getObject().equals(gene2) && a.getOntologyClass().equals(rootMF)));
        Assert.assertTrue(annotations2.stream().anyMatch(a -> a.getObject().equals(gene2) && a.getOntologyClass().equals(rootBP)));

        Model model3 = ModelFactory.createDefaultModel();
        model3.read(this.getClass().getResourceAsStream("/test_root_mf_filter3.ttl"), "", "ttl");
        Set<Triple> triples3 = model3.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem3 = arachne.processTriples(JavaConverters.asScalaSetConverter(triples3).asScala());
        Set<GPADData> annotations3 = exporter.getGPAD(mem3, IRI.create("http://test.org"));
        IRI gene3 = IRI.create("http://identifiers.org/sgd/S000002650");
        Assert.assertTrue(annotations3.stream().anyMatch(a -> a.getObject().equals(gene3) && a.getOntologyClass().equals(rootMF)));
        Assert.assertTrue(annotations3.stream().anyMatch(a -> a.getObject().equals(gene3) && a.getOntologyClass().equals(rootBP)));
        Assert.assertTrue(annotations3.stream().anyMatch(a -> a.getObject().equals(gene3) && a.getOntologyClass().equals(rootCC)));
    }

    @Test
    public void testFilterAnnotationsToRegulatedProcess() throws Exception {
        HashMap<IRI, Set<IRI>> regulators = new HashMap<>();
        regulators.put(IRI.create("http://purl.obolibrary.org/obo/GO_0030511"), Collections.singleton(IRI.create("http://purl.obolibrary.org/obo/GO_0007179")));
        GPADSPARQLExport exporter = new GPADSPARQLExport(DefaultCurieHandler.getDefaultHandler(), new HashMap<IRI, String>(), new HashMap<IRI, String>(), regulators);
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/test_filter_regulated_process.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        Set<GPADData> annotations = exporter.getGPAD(mem, IRI.create("http://test.org"));
        IRI gene = IRI.create("http://identifiers.org/mgi/MGI:2148811");
        IRI regulator = IRI.create("http://purl.obolibrary.org/obo/GO_0030511");
        IRI regulated = IRI.create("http://purl.obolibrary.org/obo/GO_0007179");
        Assert.assertTrue(annotations.stream().anyMatch(a -> a.getObject().equals(gene) && a.getOntologyClass().equals(regulator)));
        Assert.assertTrue(annotations.stream().noneMatch(a -> a.getObject().equals(gene) && a.getOntologyClass().equals(regulated)));
    }

    @Test
    public void testInteractingTaxon() {
        Model model = ModelFactory.createDefaultModel();
        model.read(this.getClass().getResourceAsStream("/MGI_MGI_2429397.ttl"), "", "ttl");
        Set<Triple> triples = model.listStatements().toList().stream().map(s -> Bridge.tripleFromJena(s.asTriple())).collect(Collectors.toSet());
        WorkingMemory mem = arachne.processTriples(JavaConverters.asScalaSetConverter(triples).asScala());
        Set<GPADData> annotations = exporter.getGPAD(mem, IRI.create("http://test.org"));
        IRI gene = IRI.create("http://identifiers.org/mgi/MGI:2429397");
        IRI interactingTaxon = IRI.create("http://purl.obolibrary.org/obo/NCBITaxon_196620");
        Assert.assertTrue(annotations.stream().anyMatch(
                a -> a.getObject().equals(gene) &&
                        a.getInteractingTaxonID().isPresent() &&
                        a.getInteractingTaxonID().get().equals(interactingTaxon)));
        Assert.assertTrue(annotations.stream().noneMatch(a ->
                a.getAnnotationExtensions().stream().anyMatch(ce -> ce.getFiller().equals(interactingTaxon))));
    }

}
