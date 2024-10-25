package org.geneontology.minerva.server;

import org.apache.jena.shex.ShapeMap;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.parser.ShExC;
import org.apache.log4j.Logger;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.geneontology.minerva.BlazegraphMolecularModelManager;
import org.geneontology.minerva.MinervaOWLGraphWrapper;
import org.geneontology.minerva.ModelReaderHelper;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.lookup.ExternalLookupService;
import org.geneontology.minerva.server.handler.*;
import org.geneontology.minerva.server.inferences.CachingInferenceProviderCreatorImpl;
import org.geneontology.minerva.server.inferences.InferenceProviderCreator;
import org.geneontology.minerva.server.validation.MinervaShexValidator;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import owltools.cli.Opts;
import owltools.gaf.eco.EcoMapperFactory;
import owltools.gaf.eco.SimpleEcoMapper;
import owltools.io.CatalogXmlIRIMapper;
import owltools.io.ParserWrapper;

import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;


public class StartUpTool {

    private static final Logger LOGGER = Logger.getLogger(StartUpTool.class);

    public static class MinervaStartUpConfig {
        // data configuration
        public String ontology = null;
        public String catalog = null;
        public String journalFile = null;
        public String exportFolder = null;
        public String modelIdPrefix = "http://model.geneontology.org/";
        public String modelIdcurie = "gomodel";

        public String defaultModelState = "development";

        public String golrUrl = null;
        public String monarchUrl = null;
        public String golrSeedUrl = null;
        public int golrCacheSize = 100000;
        public long golrCacheDuration = 24l;
        public TimeUnit golrCacheDurationUnit = TimeUnit.HOURS;
        public ExternalLookupService lookupService = null;
        public boolean checkLiteralIds = true;

        public String reasonerOpt = null;

        public CurieHandler curieHandler;

        // The subset of highly relevant relations is configured using super property
        // all direct children (asserted) are considered important
        public String importantRelationParent = null;
        public Set<OWLObjectProperty> importantRelations = null;

        // server configuration
        public int port = 6800;
        public String contextPrefix = null; // root context by default
        public String contextString = null;

        // increase default size to deal with large HTTP GET requests
        public int requestHeaderSize = 64 * 1024;
        public int requestBufferSize = 128 * 1024;

        public boolean useRequestLogging = false;

        public boolean useGolrUrlLogging = false;

        public String prefixesFile = null;

        public int sparqlEndpointTimeout = 100;

        public String shexFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shex";
        public String goshapemapFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shapeMap";
        public MinervaShexValidator shex;
        public String pathToOntologyJournal;

        public String pathToGPADSheX;

        public String pathToGPADShapeMap;

    }

    public static void main(String[] args) throws Exception {
        Opts opts = new Opts(args);
        MinervaStartUpConfig conf = new MinervaStartUpConfig();

        while (opts.hasArgs()) {
            if (opts.nextEq("-g|--graph")) {
                conf.ontology = opts.nextOpt();
            } else if (opts.nextEq("-c|--catalog")) {
                conf.catalog = opts.nextOpt();
            } else if (opts.nextEq("-f|--journal-file")) {
                conf.journalFile = opts.nextOpt();
            } else if (opts.nextEq("--export-folder")) {
                conf.exportFolder = opts.nextOpt();
            } else if (opts.nextEq("--model-id-prefix")) {
                conf.modelIdPrefix = opts.nextOpt();
            } else if (opts.nextEq("--model-id-curie")) {
                conf.modelIdcurie = opts.nextOpt();
            } else if (opts.nextEq("-p|--protein-folder")) {
                System.err.println("specific protein ontologies are no longer supported");
                System.exit(-1);
            } else if (opts.nextEq("--gaf-folder")) {
                System.err.println("--gaf-folder is not longer supported");
                System.exit(-1);
            } else if (opts.nextEq("--context-prefix")) {
                conf.contextPrefix = opts.nextOpt();
            } else if (opts.nextEq("--port")) {
                conf.port = Integer.parseInt(opts.nextOpt());
            } else if (opts.nextEq("-i|--import|--additional-import")) {
                System.err.println("-i|--import|--additional-import is no longer supported, all imports are expected to be in the source ontology '-g|--graph'");
                System.exit(-1);
            } else if (opts.nextEq("--obsolete-import")) {
                System.err.println("--obsolete-import is no longer supported");
                System.exit(-1);
            } else if (opts.nextEq("--set-relevant-relations")) {
                System.err.println("--set-relevant-relations is no longer supported, use '--set-important-relation-parent' instead");
                System.exit(-1);
            } else if (opts.nextEq("--add-relevant-relations")) {
                System.err.println("--add-relevant-relations is no longer supported, use '--set-important-relation-parent' instead");
                System.exit(-1);
            } else if (opts.nextEq("--add-relevant-relation")) {
                System.err.println("--add-relevant-relation is no longer supported, use '--set-important-relation-parent' instead");
                System.exit(-1);
            } else if (opts.nextEq("--set-important-relation-parent")) {
                conf.importantRelationParent = opts.nextOpt();
            } else if (opts.nextEq("--skip-class-id-validation")) {
                conf.checkLiteralIds = false;
            } else if (opts.nextEq("--golr-cache-size")) {
                String sizeString = opts.nextOpt();
                conf.golrCacheSize = Integer.parseInt(sizeString);
            }
            //--golr-labels http://noctua-golr.berkeleybop.org/
            else if (opts.nextEq("--golr-labels")) {
                conf.golrUrl = opts.nextOpt();
            } else if (opts.nextEq("--monarch-labels")) {
                conf.monarchUrl = opts.nextOpt();
            } else if (opts.nextEq("--golr-seed")) {
                conf.golrSeedUrl = opts.nextOpt();
            } else if (opts.nextEq("--no-reasoning|--no-reasoner")) {
                conf.reasonerOpt = null;
            } else if (opts.nextEq("--slme-hermit")) {
                conf.reasonerOpt = "slme-hermit";
            } else if (opts.nextEq("--slme-elk")) {
                conf.reasonerOpt = "slme-elk";
            } else if (opts.nextEq("--elk")) {
                conf.reasonerOpt = "elk";
            } else if (opts.nextEq("--arachne")) {
                conf.reasonerOpt = "arachne";
            } else if (opts.nextEq("--use-request-logging|--request-logging")) {
                conf.useRequestLogging = true;
            } else if (opts.nextEq("--use-golr-url-logging|--golr-url-logging")) {
                conf.useGolrUrlLogging = true;
            } else if (opts.nextEq("--prefix-mappings")) {
                conf.prefixesFile = opts.nextOpt();
            } else if (opts.nextEq("--sparql-endpoint-timeout")) {
                conf.sparqlEndpointTimeout = Integer.parseInt(opts.nextOpt());
            } else if (opts.nextEq("--ontojournal")) {
                conf.pathToOntologyJournal = opts.nextOpt();
            } else if (opts.nextEq("--gpad-shex")) {
                conf.pathToGPADSheX = opts.nextOpt();
            } else if (opts.nextEq("--gpad-shape-map")) {
                conf.pathToGPADShapeMap = opts.nextOpt();
            } else {
                break;
            }
        }
        if (conf.pathToOntologyJournal == null) {
            System.err.println("No blazegraph journal containing tbox ontology provided. exit.");
            System.exit(-1);
        }
        if (conf.ontology == null) {
            System.err.println("No ontology graph available");
            System.exit(-1);
        }
        if (conf.journalFile == null) {
            System.err.println("No journal file available");
            System.exit(-1);
        }
//		if (conf.golrUrl == null) {
//			conf.golrUrl = "http://noctua-golr.berkeleybop.org/";
//			System.err.println("No GOLR service configured.  This is required, please add e.g. --golr-labels http://noctua-golr.berkeleybop.org/ to start up parameters ");
//			//System.exit(-1);
//		} 
        conf.contextString = "/";
        if (conf.contextPrefix != null) {
            conf.contextString = "/" + conf.contextPrefix;
        }

        // set curie handler
        final CurieMappings mappings;
        if (conf.prefixesFile != null) {
            mappings = DefaultCurieHandler.loadMappingsFromFile(new File(conf.prefixesFile));
        } else {
            mappings = DefaultCurieHandler.loadDefaultMappings();
        }
        CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(conf.modelIdcurie, conf.modelIdPrefix));
        conf.curieHandler = new MappedCurieHandler(mappings, localMappings);
        // wrap the Golr service with a cache
//get rid of external look ups altogether.  
//		if (conf.golrUrl != null) {
//			conf.lookupService = new GolrExternalLookupService(conf.golrUrl, conf.curieHandler, conf.useGolrUrlLogging);
//			LOGGER.info("Setting up Golr cache with size: "+conf.golrCacheSize+" duration: "+
//					conf.golrCacheDuration+" "+conf.golrCacheDurationUnit+
//					" use url logging: "+conf.useGolrUrlLogging);
//			conf.lookupService = new CachingExternalLookupService(conf.lookupService, conf.golrCacheSize, conf.golrCacheDuration, conf.golrCacheDurationUnit);
//		}
//		if (conf.monarchUrl != null) {
//			conf.lookupService = new MonarchExternalLookupService(conf.monarchUrl, conf.curieHandler, conf.useGolrUrlLogging);
//			LOGGER.info("Setting up Monarch Golr cache with size: "+conf.golrCacheSize+" duration: "+
//					conf.golrCacheDuration+" "+conf.golrCacheDurationUnit+
//					" use url logging: "+conf.useGolrUrlLogging);
//			conf.lookupService = new CachingExternalLookupService(conf.lookupService, conf.golrCacheSize, conf.golrCacheDuration, conf.golrCacheDurationUnit);
//		}

        //TODO maybe make these command line parameters
        URL shex_schema_url = new URL(conf.shexFileUrl);
        File shex_schema_file = new File("./target/shex-schema.shex");
        org.apache.commons.io.FileUtils.copyURLToFile(shex_schema_url, shex_schema_file);
        URL shex_map_url = new URL(conf.goshapemapFileUrl);
        File shex_map_file = new File("./target/go-cam-shapes.shapeMap");
        org.apache.commons.io.FileUtils.copyURLToFile(shex_map_url, shex_map_file);
        //reasoner set in next phase after ontologies loaded
        conf.shex = new MinervaShexValidator(shex_schema_file, shex_map_file, conf.curieHandler, null);

        Server server = startUp(conf);
        try {
            server.join();
        } finally {
            server.stop();
            server.destroy();
        }
    }

    /**
     * Try to resolve the given string into an {@link OWLObjectProperty}.
     *
     * @param rel
     * @param g
     * @return property or null
     */
    public static OWLObjectProperty getRelation(String rel, MinervaOWLGraphWrapper g) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        if (rel.startsWith("http://")) {
            IRI iri = IRI.create(rel);
            return g.getDataFactory().getOWLObjectProperty(iri);
        }
        // try to find property
        OWLObjectProperty p = g.getOWLObjectPropertyByIdentifier(rel);
        if (p == null) {
            // could not find by id, search by label
            OWLObject owlObject = g.getOWLObjectByLabel(rel);
            if (owlObject instanceof OWLObjectProperty) {
                p = (OWLObjectProperty) owlObject;
            }
        }
        return p;
    }

    /**
     * Find all asserted direct sub properties of the parent property.
     *
     * @param parent
     * @param g
     * @return set
     */
    public static Set<OWLObjectProperty> getAssertedSubProperties(OWLObjectProperty parent, MinervaOWLGraphWrapper g) {
        Set<OWLObjectProperty> properties = new HashSet<OWLObjectProperty>();
        for (OWLOntology ont : g.getAllOntologies()) {
            Set<OWLSubObjectPropertyOfAxiom> axioms = ont.getObjectSubPropertyAxiomsForSuperProperty(parent);
            for (OWLSubObjectPropertyOfAxiom axiom : axioms) {
                OWLObjectPropertyExpression subProperty = axiom.getSubProperty();
                if (subProperty instanceof OWLObjectProperty) {
                    properties.add(subProperty.asOWLObjectProperty());
                }
            }
        }
        return properties;
    }

    public static Server startUp(final MinervaStartUpConfig conf)
            throws Exception {
        // load ontology
        LOGGER.info("Start loading ontology: " + conf.ontology);
        ParserWrapper pw = new ParserWrapper();
        // if available, set catalog
        if (conf.catalog != null) {
            LOGGER.info("Adding catalog xml: " + conf.catalog);
            pw.addIRIMapper(new CatalogXmlIRIMapper(conf.catalog));
        }
        MinervaOWLGraphWrapper graph = pw.parseToOWLGraph(conf.ontology);

        //grab ontology metadata and store for status service
        Map<IRI, Set<OWLAnnotation>> ont_annos = new HashMap<IRI, Set<OWLAnnotation>>();
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        OWLAnnotationProperty versionIRI = factory.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_VERSION_IRI.getIRI());
        for (OWLOntology ont : graph.getAllOntologies()) {
            Set<OWLAnnotation> maybeVersionAnnotation = ont.getOntologyID().getVersionIRI().transform(iri -> factory.getOWLAnnotation(versionIRI, iri)).asSet();
            Set<OWLAnnotation> annotations = ont.getAnnotations();
            annotations.addAll(maybeVersionAnnotation);
            ont_annos.put(ont.getOWLOntologyManager().getOntologyDocumentIRI(ont), annotations);
        }

        OWLOntology full_tbox = forceMergeImports(graph.getSourceOntology(), graph.getAllOntologies());
        graph.setSourceOntology(full_tbox);

        if (conf.importantRelationParent != null) {
            // try to find parent property
            OWLObjectProperty parentProperty = getRelation(conf.importantRelationParent, graph);
            if (parentProperty != null) {
                // find all asserted direct sub properties of the parent property
                conf.importantRelations = getAssertedSubProperties(parentProperty, graph);
                if (conf.importantRelations.isEmpty()) {
                    LOGGER.warn("Could not find any asserted sub properties for parent: " + conf.importantRelationParent);
                }
            } else {
                LOGGER.warn("Could not find a property for rel: " + conf.importantRelationParent);
            }
        }

        // set folder to  models
        LOGGER.info("Model path: " + conf.journalFile);

        // create model manager
        LOGGER.info("Start initializing Minerva");
        UndoAwareMolecularModelManager models = new UndoAwareMolecularModelManager(graph.getSourceOntology(),
                conf.curieHandler, conf.modelIdPrefix, conf.journalFile, conf.exportFolder, conf.pathToOntologyJournal, true);
        // set pre and post file handlers
        models.addPostLoadOntologyFilter(ModelReaderHelper.INSTANCE);
        if (conf.pathToGPADSheX != null && conf.pathToGPADShapeMap != null) {
            ShexSchema schema = ShExC.parse(conf.pathToGPADSheX);
            ShapeMap shapeMap = ShExC.parseShapeMap(conf.pathToGPADShapeMap);
            models.addPreFileSaveHandler(new BlazegraphMolecularModelManager.ShexValidatingPreSaveFileHandler(schema, shapeMap, "http://geneontology.org/lego/conforms_to_gpad"));
        }
        //	conf.shex.tbox_reasoner = models.getTbox_reasoner();
        conf.shex.setGo_lego_repo(models.getGolego_repo());
        conf.shex.curieHandler = conf.curieHandler;
        // start server
        Server server = startUp(models, conf, ont_annos);
        return server;
    }

    public static OWLOntology forceMergeImports(OWLOntology sourceOntology, Set<OWLOntology> import_set) {

        //In some cases, go-lego is not pre-merged and parseToOWLgraph keeps the imports separate
        //most OWL API methods have an include-imports option that makes this work
        //but EntitySearcher methods that deal with annotation assertions do not.
        //The current pattern for mapping external ontologies to local ones (e.g. reactome to uniprot)
        //involves the use of an annotation property..  To get that to work,
        //need to pre-merge the ontologies.
        OWLOntology full_tbox = sourceOntology;
        if (import_set != null) {
            for (OWLOntology ont : import_set) {
                if (!ont.equals(full_tbox)) {
                    full_tbox.getOWLOntologyManager().addAxioms(full_tbox, ont.getAxioms());
                    full_tbox.getOWLOntologyManager().removeOntology(ont);
                }
            }
        }
        return full_tbox;
    }

    public static InferenceProviderCreator createInferenceProviderCreator(String reasonerOpt, UndoAwareMolecularModelManager models, MinervaShexValidator shex) {
        switch (reasonerOpt) {
            //	case ("slme-hermit"): return CachingInferenceProviderCreatorImpl.createHermiT(shex);
            case ("slme-elk"):
                return CachingInferenceProviderCreatorImpl.createElk(true, shex);
            case ("elk"):
                return CachingInferenceProviderCreatorImpl.createElk(false, shex);
            case ("arachne"):
                return CachingInferenceProviderCreatorImpl.createArachne(models.getRuleEngine(), shex);
            default:
                return null;
        }
    }

    public static Server startUp(UndoAwareMolecularModelManager models, MinervaStartUpConfig conf, Map<IRI, Set<OWLAnnotation>> ont_annos)
            throws Exception {

        LOGGER.info("Setup Jetty config.");
        // Configuration: Use an already existing handler instance
        // Configuration: Use custom JSON renderer (GSON)
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.register(GsonMessageBodyHandler.class);
        resourceConfig.register(RequireJsonpFilter.class);
        resourceConfig.register(SPARQLResultsMessageBodyWriter.class);
        resourceConfig.register(SPARQLGraphMessageBodyWriter.class);
        if (conf.useRequestLogging) {
            resourceConfig.register(LoggingApplicationEventListener.class);
        }
        //resourceConfig.register(AuthorizationRequestFilter.class);

        LOGGER.info("BatchHandler config inference provider: " + conf.reasonerOpt);
        LOGGER.info("BatchHandler config importantRelations: " + conf.importantRelations);
        LOGGER.info("BatchHandler config lookupService: " + conf.lookupService);
        LOGGER.info("BatchHandler config checkLiteralIds: " + conf.checkLiteralIds);
        LOGGER.info("BatchHandler config useRequestLogging: " + conf.useRequestLogging);
        if (conf.golrSeedUrl == null) {
            // default fall back to normal golr URL
            conf.golrSeedUrl = conf.golrUrl;
        }
        LOGGER.info("SeedHandler config golrUrl: " + conf.golrSeedUrl);

        InferenceProviderCreator ipc = createInferenceProviderCreator(conf.reasonerOpt, models, conf.shex);
        JsonOrJsonpBatchHandler batchHandler = new JsonOrJsonpBatchHandler(models, conf.defaultModelState,
                ipc, conf.importantRelations, conf.lookupService);
        batchHandler.CHECK_LITERAL_IDENTIFIERS = false; //conf.checkLiteralIds;

        SimpleEcoMapper ecoMapper = EcoMapperFactory.createSimple();
        //		JsonOrJsonpSeedHandler seedHandler = new JsonOrJsonpSeedHandler(models, conf.defaultModelState, conf.golrSeedUrl, ecoMapper );
        //	SPARQLHandler sparqlHandler = new SPARQLHandler(models, conf.sparqlEndpointTimeout);
        ModelSearchHandler searchHandler = new ModelSearchHandler(models);
        ModelARTHandler artHandler = new ModelARTHandler(models, ipc);

        LocalDate d = LocalDate.now();
        LocalTime t = LocalTime.now();
        String startup = d.toString() + " " + t.toString();
        StatusHandler statusHandler = new StatusHandler(conf, ont_annos, startup);
        TaxonHandler taxonHandler = new TaxonHandler(models);
        resourceConfig = resourceConfig.registerInstances(batchHandler, searchHandler, artHandler, statusHandler, taxonHandler);

        // setup jetty server port, buffers and context path
        Server server = new Server();
        // create connector with port and custom buffer sizes
        //old jetty
        //SelectChannelConnector connector = new SelectChannelConnector();
        //old jetty - they must be configured somewhere else in new jetty
        //connector.setRequestHeaderSize(conf.requestHeaderSize);
        //connector.setRequestBufferSize(conf.requestBufferSize);
        //new jetty - does not have setRequestBufferSize at all
        //seems to push defaults harder here.
        //to change request header size need to create a new connector and manipulate httpconfiguration
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setRequestHeaderSize(conf.requestHeaderSize);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(http_config));
        connector.setPort(conf.port);

        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(conf.contextString);
        server.setHandler(context);
        ServletHolder h = new ServletHolder(new ServletContainer(resourceConfig));
        context.addServlet(h, "/*");

        // start jetty server
        LOGGER.info("Start server on port: " + conf.port + " context: " + conf.contextString);
        server.start();
        return server;
    }
}
