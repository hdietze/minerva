package org.geneontology.minerva.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.geneontology.minerva.BlazegraphMolecularModelManager;
import org.geneontology.minerva.ModelContainer;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.InferenceProvider;
import org.geneontology.minerva.json.JsonModel;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.legacy.sparql.GPADSPARQLExport;
import org.geneontology.minerva.lookup.GolrExternalLookupService;
import org.geneontology.minerva.lookup.ExternalLookupService.LookupEntry;
import org.geneontology.minerva.server.StartUpTool;
import org.geneontology.minerva.server.inferences.InferenceProviderCreator;
import org.geneontology.minerva.server.validation.MinervaShexValidator;
import org.geneontology.minerva.util.BlazegraphMutationCounter;
import org.geneontology.minerva.validation.Enricher;
import org.geneontology.minerva.validation.ShexValidationReport;
import org.geneontology.minerva.validation.ShexValidator;
import org.geneontology.minerva.validation.ValidationResultSet;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.UpdateExecutionException;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;
import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import owltools.cli.Opts;
import owltools.io.ParserWrapper;


public class CommandLineInterface {
	private static final Logger LOGGER = Logger.getLogger(CommandLineInterface.class);
	
	public static void main(String[] args) throws Exception {
		Options main_options = new Options();
		OptionGroup methods = new OptionGroup();
		methods.setRequired(true);
		Option dump = Option.builder()
	            .longOpt("dump-owl-models")
	            .desc("export OWL GO-CAM models from journal")
	            .hasArg(false)
	            .build();
		methods.addOption(dump);
		Option import_owl = Option.builder()
	            .longOpt("import-owl-models")
	            .desc("import OWL GO-CAM models into journal")
	            .hasArg(false)
	            .build();
		methods.addOption(import_owl);
		Option sparql = Option.builder()
	            .longOpt("sparql-update")
	            .desc("update the blazegraph journal with the given sparql statement")
	            .hasArg(false)
	            .build();
		methods.addOption(sparql);
		Option json = Option.builder()
	            .longOpt("owl-lego-to-json")
	            .desc("Given a GO-CAM OWL file, make its minerva json represention")
	            .hasArg(false)
	            .build();
		methods.addOption(json);
		Option gpad = Option.builder()
	            .longOpt("lego-to-gpad-sparql")
	            .desc("Given a GO-CAM journal, export GPAD representation for all the go-cams")
	            .hasArg(false)
	            .build();
		methods.addOption(gpad);
		Option version = Option.builder()
	            .longOpt("version")
	            .desc("Print the version of the minerva stack used here.  Extracts this from JAR file.")
	            .hasArg(false)
	            .build();
		methods.addOption(version);
		Option validate = Option.builder("v")
        .longOpt("validate-go-cams")
        .desc("Check a collection of go-cam files or a journal for valid semantics (owl) and structure (shex)")
        .hasArg(false)
        .build();
		methods.addOption(validate);
		//update-gene-product-types
		Option update_gps = Option.builder()
		        .longOpt("update-gene-product-types")
		        .desc("Given a directory of go-cam ttl files, assert the root type (e.g. protein) for each gene product instance")
		        .hasArg(false)
		        .build();
		methods.addOption(update_gps);
		main_options.addOptionGroup(methods);
				
		CommandLineParser parser = new DefaultParser();
		try {
		CommandLine cmd = parser.parse( main_options, args, true);

		if(cmd.hasOption("dump-owl-models")) {
			Options dump_options = new Options();
			dump_options.addOption(dump);
			dump_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
			dump_options.addOption("f", "folder", true, "Sets the output folder the GO-CAM model files");
			dump_options.addOption("p", "model-id-prefix", true, "prefix for GO-CAM model ids");
			cmd = parser.parse( dump_options, args, false);
			String journalFilePath = cmd.getOptionValue("j"); //--journal
			String outputFolder = cmd.getOptionValue("f"); //--folder
			String modelIdPrefix = cmd.getOptionValue("p"); //--prefix
			modelsToOWL(journalFilePath, outputFolder, modelIdPrefix);
		}else if(cmd.hasOption("import-owl-models")) {
			Options import_options = new Options();
			import_options.addOption(import_owl);
			import_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
			import_options.addOption("f", "folder", true, "Sets the input folder the GO-CAM model files");
			cmd = parser.parse( import_options, args, false);
			String journalFilePath = cmd.getOptionValue("j"); //--journal
			String outputFolder = cmd.getOptionValue("f"); //--folder
			importOWLModels(journalFilePath, outputFolder);
		}else if(cmd.hasOption("sparql-update")) {
			Options sparql_options = new Options();
			sparql_options.addOption(sparql);
			sparql_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
			sparql_options.addOption("f", "file", true, "Sets the file containing a SPARQL update");
			cmd = parser.parse( sparql_options, args, false);
			String journalFilePath = cmd.getOptionValue("j"); //--journal
			String file = cmd.getOptionValue("f");
			sparqlUpdate(journalFilePath, file);
		}else if(cmd.hasOption("owl-lego-to-json")) {		
			Options json_options = new Options();
			json_options.addOption(json);
			json_options.addOption("i", "OWLFile", true, "Input GO-CAM OWL file");
			json_options.addOption("o", "JSONFILE", true, "Output JSON file");
			OptionGroup format = new OptionGroup();
			Option pretty = Option.builder()
		            .longOpt("pretty-json")
		            .desc("pretty json format")
		            .hasArg(false)
		            .build();
			format.addOption(pretty);
			Option compact = Option.builder()
		            .longOpt("compact-json")
		            .desc("compact json format")
		            .hasArg(false)
		            .build();
			format.addOption(compact);
			json_options.addOptionGroup(format);
			cmd = parser.parse( json_options, args, false);		
			String input = cmd.getOptionValue("i");
			String output = cmd.getOptionValue("o");
			boolean usePretty = true;
			if(cmd.hasOption("compact-json")) {
				usePretty = false;
			}
			owl2LegoJson(input, output, usePretty);
		}else if(cmd.hasOption("lego-to-gpad-sparql")) {
			Options gpad_options = new Options();
			gpad_options.addOption(gpad);
			gpad_options.addOption("i", "input", true, "Sets the Blazegraph journal file for the database");
			gpad_options.addOption("o", "gpad-output", true, "Sets the output location for the GPAD");
			gpad_options.addOption("p", "model-id-prefix", true, "prefix for GO-CAM model ids");
			gpad_options.addOption("c", "model-id-curie", true, "prefix for GO-CAM curies");
			gpad_options.addOption("ont", "ontology", true, "IRI of tbox ontology for classification - usually default go-lego.owl");
			gpad_options.addOption("cat", "catalog", true, "Catalog file for tbox ontology. " + 
										"Use this to specify local copies of the ontology and or its imports to " + 
										"speed and control the process. If not used, will download the tbox and all its imports.");
			cmd = parser.parse(gpad_options, args, false);
			String inputDB = cmd.getOptionValue("input");
			String gpadOutputFolder = cmd.getOptionValue("gpad-output");
			String modelIdPrefix = cmd.getOptionValue("model-id-prefix");
			String modelIdcurie = cmd.getOptionValue("model-id-curie");
			String ontologyIRI = cmd.getOptionValue("ontology");
			String catalog = cmd.getOptionValue("catalog");
			legoToAnnotationsSPARQL(modelIdPrefix, modelIdcurie, inputDB, gpadOutputFolder, ontologyIRI, catalog);
		}else if(cmd.hasOption("version")) {
			printVersion();
		}else if(cmd.hasOption("validate-go-cams")) {
			Options validate_options = new Options();
			validate_options.addOption(validate);
			validate_options.addOption("i", "input", true, "Either a blazegrpah journal or a folder with go-cams in it");
			validate_options.addOption("shex", "shex", false, "If present, will execute shex validation");
			validate_options.addOption("owl", "owl", false, "If present, will execute shex validation");
			validate_options.addOption("r", "report-file", true, "Main output file for the validation");
			validate_options.addOption("e", "explanation-output-file", true, "Explanations for failed validations");
			validate_options.addOption("p", "model-id-prefix", true, "prefix for GO-CAM model ids");
			validate_options.addOption("cu", "model-id-curie", true, "prefix for GO-CAM curies");
			validate_options.addOption("ont", "ontology", true, "IRI of tbox ontology - usually default go-lego.owl");
			validate_options.addOption("c", "catalog", true, "Catalog file for tbox ontology.  "
					+ "Use this to specify local copies of the ontology and or its imports to "
					+ "speed and control the process. If not used, will download the tbox and all its imports.");
			validate_options.addOption("shouldfail", "shouldfail", false, "When used in travis mode for tests, shouldfail "
					+ "parameter will allow a successful run on a folder that only contains incorrect models.");
			validate_options.addOption("t", "travis", false, "If travis, then the program will stop upon a failed "
					+ "validation and report an error.  Otherwise it will continue to test all the models.");
			validate_options.addOption("m", "shapemap", true, "Specify a shapemap file.  Otherwise will download from go_shapes repo.");
			validate_options.addOption("s", "shexpath", true, "Specify a shex schema file.  Otherwise will download from go_shapes repo.");
			
			cmd = parser.parse(validate_options, args, false);
			String input = cmd.getOptionValue("input");			
			String basicOutputFile = cmd.getOptionValue("report-file");
			String shexpath = cmd.getOptionValue("s");
			String shapemappath = cmd.getOptionValue("shapemap");
			
			String explanationOutputFile = cmd.getOptionValue("explanation-output-file");
			String ontologyIRI = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
			if(cmd.hasOption("ontology")) {
				ontologyIRI = cmd.getOptionValue("ontology");
			}
			String catalog = cmd.getOptionValue("catalog");
			String modelIdPrefix = "http://model.geneontology.org/";
			if(cmd.hasOption("model-id-prefix")) {
				modelIdPrefix = cmd.getOptionValue("model-id-prefix");
			}			
			String modelIdcurie = "gomodel";
			if(cmd.hasOption("model-id-curie")) {
				modelIdcurie = cmd.getOptionValue("model-id-curie");
			}			
			boolean travisMode = false;
			if(cmd.hasOption("travis")) {
				travisMode = true;
			}
			boolean shouldFail = false;
			if(cmd.hasOption("shouldfail")) {
				shouldFail = true;
			}
			boolean checkShex = false;
			if(cmd.hasOption("shex")) {
				checkShex = true;
			}
			validateGoCams(input, basicOutputFile, explanationOutputFile, ontologyIRI, catalog, modelIdPrefix, modelIdcurie, shexpath, shapemappath, travisMode, shouldFail, checkShex);
		}else if(cmd.hasOption("update-gene-product-types")) {
			Options options = new Options();
			options.addOption(update_gps);
			options.addOption("i", "input", true, "Sets the folder of GO-CAM ttl files to update");
			options.addOption("o", "output", true, "Sets the output folder the updated GO-CAM files");
			options.addOption("n", "neo", true, "Sets the location of the neo file.");
			options.addOption("c", "catalog", true, "Sets the location for the catalog file for handling go-lego.owl imports");
			try {
				cmd = parser.parse( options, args, false);
				String neo_file = cmd.getOptionValue("n"); 
				String catalog = cmd.getOptionValue("c"); 
				String input_dir = cmd.getOptionValue("i"); 
				String output_dir = cmd.getOptionValue("o"); 		
				TypeUpdater updater = new TypeUpdater(neo_file, catalog);
				updater.runBatchUpdate(input_dir, output_dir);
			}catch( ParseException exp ) {
				System.out.println( "Unexpected exception:" + exp.getMessage() );
			}
		}
		}catch( ParseException exp ) {
		    System.out.println( "Unexpected exception:" + exp.getMessage() );
		}
	}
	
	/**
	 * Given a blazegraph journal with go-cams in it, write them all out as OWL files.
	 * cli --dump-owl-models
	 * @param journalFilePath
	 * @param outputFolder
	 * @param modelIdPrefix
	 * @throws Exception
	 */
	public static void modelsToOWL(String journalFilePath, String outputFolder, String modelIdPrefix) throws Exception {
		if(modelIdPrefix==null) {
			modelIdPrefix = "http://model.geneontology.org/";
		}

		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			System.exit(-1);
			return;
		}
		if (outputFolder == null) {
			System.err.println("No output folder was configured.");
			System.exit(-1);
			return;
		}

		OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
		CurieHandler curieHandler = new MappedCurieHandler();
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, outputFolder);
		m3.dumpAllStoredModels();
		m3.dispose();
	}
	
	/**
	 * Load the go-cam files in the input folder into the journal
	 * cli import-owl-models
	 * @param journalFilePath
	 * @param inputFolder
	 * @throws Exception
	 */
	public static void importOWLModels(String journalFilePath, String inputFolder) throws Exception {
		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			System.exit(-1);
			return;
		}
		if (inputFolder == null) {
			System.err.println("No input folder was configured.");
			System.exit(-1);
			return;
		}

		OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
		String modelIdPrefix = "http://model.geneontology.org/"; // this will not be used for anything
		CurieHandler curieHandler = new MappedCurieHandler();
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, null);
		for (File file : FileUtils.listFiles(new File(inputFolder), null, true)) {
			LOGGER.info("Loading " + file);
			if(file.getName().endsWith("ttl")||file.getName().endsWith("owl")) {
				m3.importModelToDatabase(file, true);
			}else {
				LOGGER.info("Ignored for not ending with .ttl or .owl " + file);
			}
		}
		m3.dispose();
	}
	
	/**
	 * Updates the journal with the provided update sparql statement.
	 * cli parameter --sparql-update
	 * @param journalFilePath
	 * @param updateFile
	 * @throws OWLOntologyCreationException
	 * @throws IOException
	 * @throws RepositoryException
	 * @throws MalformedQueryException
	 * @throws UpdateExecutionException
	 */
	public static void sparqlUpdate(String journalFilePath, String updateFile) throws OWLOntologyCreationException, IOException, RepositoryException, MalformedQueryException, UpdateExecutionException {
		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			System.exit(-1);
			return;
		}
		if (updateFile == null) {
			System.err.println("No update file was configured.");
			System.exit(-1);
			return;
		}

		String update = FileUtils.readFileToString(new File(updateFile), StandardCharsets.UTF_8);
		Properties properties = new Properties();
		properties.load(CommandLineInterface.class.getResourceAsStream("/org/geneontology/minerva/blazegraph.properties"));
		properties.setProperty(com.bigdata.journal.Options.FILE, journalFilePath);
		
		BigdataSail sail = new BigdataSail(properties);
		BigdataSailRepository repository = new BigdataSailRepository(sail);
		repository.initialize();
		BigdataSailRepositoryConnection conn = repository.getUnisolatedConnection();
		BlazegraphMutationCounter counter = new BlazegraphMutationCounter();
		conn.addChangeLog(counter);
		conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
		int changes = counter.mutationCount();
		conn.removeChangeLog(counter);
		System.out.println("\nApplied " + changes + " changes");
		conn.close();
	}

	/**
	 * Convert a GO-CAM owl file to a minerva json structure
	 * --owl-lego-to-json
	 * @param input
	 * @param output
	 * @param usePretty
	 * @throws Exception
	 */
	public static void owl2LegoJson(String input, String output, boolean usePretty) throws Exception {

		// minimal inputs
		if (input == null) {
			System.err.println("No input model was configured.");
			System.exit(-1);
			return;
		}
		if (output == null) {
			System.err.println("No output file was configured.");
			System.exit(-1);
			return;
		}

		// configuration
		CurieHandler curieHandler = DefaultCurieHandler.getDefaultHandler();
		GsonBuilder gsonBuilder = new GsonBuilder();
		if (usePretty) {
			gsonBuilder.setPrettyPrinting();
		}
		Gson gson = gsonBuilder.create();

		// process each model
		if (LOGGER.isInfoEnabled()) {
			LOGGER.info("Loading model from file: "+input);
		}
		OWLOntology model = null;
		final JsonModel jsonModel;
		ParserWrapper pw = new ParserWrapper();
		try {
			
			// load model
			model = pw.parseOWL(IRI.create(new File(input).getCanonicalFile()));
			InferenceProvider inferenceProvider = null; // TODO decide if we need reasoning
			String modelId = null;
			Optional<IRI> ontologyIRI = model.getOntologyID().getOntologyIRI();
			if (ontologyIRI.isPresent()) {
				modelId = curieHandler.getCuri(ontologyIRI.get());
			}

			// render json
			final MolecularModelJsonRenderer renderer = new MolecularModelJsonRenderer(modelId, model, inferenceProvider, curieHandler);
			jsonModel = renderer.renderModel();
		}
		finally {
			if (model != null) {
				pw.getManager().removeOntology(model);
				model = null;
			}
		}

		// save as json string
		final String json = gson.toJson(jsonModel);
		final File outputFile = new File(output).getCanonicalFile();
		try (OutputStream outputStream = new FileOutputStream(outputFile)) {
			if (LOGGER.isInfoEnabled()) {
				LOGGER.info("Saving json to file: "+outputFile);
			}
			IOUtils.write(json, outputStream);
		}
	}

	/**
	 * Output GPAD files via inference+SPARQL
	 * cli --lego-to-gpad-sparql
	 * @param modelIdPrefix
	 * @param modelIdcurie
	 * @param inputDB
	 * @param gpadOutputFolder
	 * @param ontologyIRI
	 * @throws Exception
	 */
	public static void legoToAnnotationsSPARQL(String modelIdPrefix, String modelIdcurie, String inputDB, String gpadOutputFolder, String ontologyIRI, String catalog) throws Exception {
		if(modelIdPrefix==null) {
			modelIdPrefix = "http://model.geneontology.org/";
		}
		if(modelIdcurie==null) {
			modelIdcurie = "gomodel";
		}
		if(inputDB==null) { 
			inputDB = "blazegraph.jnl";
		}
		if(gpadOutputFolder==null) {
			gpadOutputFolder = null;
		}
		if(ontologyIRI==null) {
			ontologyIRI = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
		}
		OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
		if(catalog!=null) {
			LOGGER.info("using catalog: "+catalog);
			ontman.setIRIMappers(Sets.newHashSet(new owltools.io.CatalogXmlIRIMapper(catalog)));
		}else {
			LOGGER.info("no catalog, resolving all ontology uris directly");
		}
		
		OWLOntology ontology = ontman.loadOntology(IRI.create(ontologyIRI));
		CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
		CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(ontology, curieHandler, modelIdPrefix, inputDB, null);
		final String immutableModelIdPrefix = modelIdPrefix;
		final String immutableGpadOutputFolder = gpadOutputFolder;
		m3.getAvailableModelIds().stream().parallel().forEach(modelIRI -> {
			try {
				String gpad = new GPADSPARQLExport(curieHandler, m3.getLegacyRelationShorthandIndex(), m3.getTboxShorthandIndex(), m3.getDoNotAnnotateSubset()).exportGPAD(m3.createInferredModel(modelIRI));
				String fileName = StringUtils.replaceOnce(modelIRI.toString(), immutableModelIdPrefix, "") + ".gpad";
				Writer writer = new OutputStreamWriter(new FileOutputStream(Paths.get(immutableGpadOutputFolder, fileName).toFile()), StandardCharsets.UTF_8);
				writer.write(gpad);
				writer.close();
			} catch (InconsistentOntologyException e) {
				LOGGER.error("Inconsistent ontology: " + modelIRI);
			} catch (IOException e) {
				LOGGER.error("Couldn't export GPAD for: " + modelIRI, e);
			}
		});
		m3.dispose();
	}
	
	
	/**
	 * --validate-go-cams
	 * -i /GitHub/GO_Shapes/test_ttl/go_cams/should_pass/ 
	 * -c ./catalog-no-import.xml
	 * @param input
	 * @param basicOutputFile
	 * @param explanationOutputFile
	 * @param ontologyIRI
	 * @param catalog
	 * @param modelIdPrefix
	 * @param modelIdcurie
	 * @param shexpath
	 * @param shapemappath
	 * @param travisMode
	 * @param shouldPass
	 * @throws Exception
	 */
	public static void validateGoCams(String input, String basicOutputFile, String explanationOutputFile, 
			String ontologyIRI, String catalog, String modelIdPrefix, String modelIdcurie, 
			String shexpath, String shapemappath, boolean travisMode, boolean shouldFail, boolean checkShex) throws Exception {
		Logger LOG = Logger.getLogger(BlazegraphMolecularModelManager.class);
		LOG.setLevel(Level.ERROR);
		LOGGER.setLevel(Level.INFO);
		String inputDB = "blazegraph.jnl";
		String shexFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shex";
		String goshapemapFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shapeMap";

		Map<String, String> modelid_filename = new HashMap<String, String>();
	
		if(basicOutputFile==null) {
			LOGGER.error("please specify an output file with --output-file ");
			System.exit(-1);
		}
		if(input==null) {
			LOGGER.error("please provide an input file - either a directory of ttl files or a blazegraph journal");
			System.exit(-1);
		}
		if(input.endsWith(".jnl")) {
			inputDB = input;
			LOGGER.info("loaded blazegraph journal: "+input);
		}else {
			LOGGER.info("no journal found, trying as directory: "+input);
			File i = new File(input);
			if(i.exists()) {
				//remove anything that existed earlier
				File bgdb = new File(inputDB);
				if(bgdb.exists()) {
					bgdb.delete();
				}
				//load everything into a bg journal
				OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
				CurieHandler curieHandler = new MappedCurieHandler();
				BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, inputDB, null);
				if(i.isDirectory()) {
					FileUtils.listFiles(i, null, true).parallelStream().parallel().forEach(file-> {
						//for (File file : 
						//m3.getAvailableModelIds().stream().parallel().forEach(modelIRI -> {
						if(file.getName().endsWith(".ttl")||file.getName().endsWith("owl")) {
							LOGGER.info("Loading " + file);
							try {
								String modeluri = m3.importModelToDatabase(file, true);
								modelid_filename.put(modeluri, file.getName());
							} catch (OWLOntologyCreationException | RepositoryException | RDFParseException
									| RDFHandlerException | IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						} 
					});
				}else {
					LOGGER.info("Loading " + i);
					m3.importModelToDatabase(i, true);
				}
				LOGGER.info("loaded files into blazegraph journal: "+input);
				m3.dispose();
			}
		}
		LOGGER.info("loading tbox ontology: "+ontologyIRI);
		OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
		if(catalog!=null) {
			LOGGER.info("using catalog: "+catalog);
			ontman.setIRIMappers(Sets.newHashSet(new owltools.io.CatalogXmlIRIMapper(catalog)));
		}else {
			LOGGER.info("no catalog, resolving all ontology uris directly");
		}
		OWLOntology tbox_ontology = ontman.loadOntology(IRI.create(ontologyIRI));
		tbox_ontology = StartUpTool.forceMergeImports(tbox_ontology, tbox_ontology.getImports());
		LOGGER.info("ontology axioms loaded: "+tbox_ontology.getAxiomCount());
		LOGGER.info("building model manager and structural reasoner");
		CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
		CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
		UndoAwareMolecularModelManager m3 = new UndoAwareMolecularModelManager(tbox_ontology, curieHandler, modelIdPrefix, inputDB, null);
		String reasonerOpt = "arachne"; 
		LOGGER.info("tbox reasoner for shex "+m3.getTbox_reasoner().getReasonerName());		
		if(shexpath==null) {
			//fall back on downloading from shapes repo
			URL shex_schema_url = new URL(shexFileUrl);
			shexpath = "./go-cam-schema.shex";
			File shex_schema_file = new File(shexpath);
			org.apache.commons.io.FileUtils.copyURLToFile(shex_schema_url, shex_schema_file);			
			System.err.println("-s .No shex schema provided, using: "+shexFileUrl);
		}
		if(shapemappath==null) {
			URL shex_map_url = new URL(goshapemapFileUrl);
			shapemappath = "./go-cam-shapes.shapeMap";
			File shex_map_file = new File(shapemappath);
			org.apache.commons.io.FileUtils.copyURLToFile(shex_map_url, shex_map_file);
			System.err.println("-m .No shape map file provided, using: "+goshapemapFileUrl);
		}
		MinervaShexValidator shex = new MinervaShexValidator(shexpath, shapemappath, curieHandler, m3.getTbox_reasoner());
		if(checkShex) {
			if(checkShex) {
				shex.setActive(true);
			}else {
				shex.setActive(false);
			}
		}
		LOGGER.info("Building OWL inference provider: "+reasonerOpt);
		InferenceProviderCreator ipc = StartUpTool.createInferenceProviderCreator(reasonerOpt, m3, shex);
		LOGGER.info("Validating models: "+reasonerOpt);

		FileWriter basic_output = new FileWriter(basicOutputFile);
		if(explanationOutputFile!=null) {
			FileWriter explanations = new FileWriter(explanationOutputFile, false);
			explanations.write("Explanations of invalid models.\n");
			explanations.close();
		}
		try {
			basic_output.write("filename\tmodel_id\tOWL_consistent\tshex_valid\n");
			final boolean shex_output = checkShex;			
			m3.getAvailableModelIds().stream().forEach(modelIRI -> {
				try {
					String filename = modelid_filename.get(modelIRI.toString());
					boolean isConsistent = true;
					boolean isConformant = true;
					LOGGER.info("processing "+filename+"\t"+modelIRI);
					ModelContainer mc = m3.getModel(modelIRI);		
					InferenceProvider ip = ipc.create(mc);
					isConsistent = ip.isConsistent();
					if(!isConsistent&&explanationOutputFile!=null) {
						FileWriter explanations = new FileWriter(explanationOutputFile, true);
						explanations.write(filename+"\t"+modelIRI+"\n\tOWL fail explanation: "+ip.getValidation_results().getOwlvalidation().getAsText()+"\n");
						explanations.close();
					}
					if(travisMode&&!isConsistent) {
						if(!shouldFail) {
							LOGGER.error(filename+"\t"+modelIRI+"\tOWL:is inconsistent, quitting");							
							System.exit(-1);
						}
					}else if(travisMode&&isConsistent&&shouldFail) {
							LOGGER.error(filename+"\t"+modelIRI+"\tOWL:is consistent, but it should not be, quitting");
							System.exit(-1);
					}
					if(shex_output) {
						ValidationResultSet validations = ip.getValidation_results();
						isConformant = validations.allConformant();	
						if(!isConformant&&explanationOutputFile!=null) {
							FileWriter explanations = new FileWriter(explanationOutputFile, true);
							explanations.write(filename+"\t"+modelIRI+"\n\tSHEX fail explanation: "+ip.getValidation_results().getShexvalidation().getAsText()+"\n");
							explanations.close();
						}
						if(travisMode) {
							if(!isConformant&&!shouldFail) {
								LOGGER.error(filename+"\t"+modelIRI+"\tshex is nonconformant, quitting, explanation:\n"+ip.getValidation_results().getShexvalidation().getAsText());
								System.exit(-1);
							}else if(isConformant&&shouldFail) {
								LOGGER.error(filename+"\t"+modelIRI+"\tshex validates, but it should not be, quitting");
								System.exit(-1);
							}
						}			
						LOGGER.info(filename+"\t"+modelIRI+"\tOWL:"+isConsistent+"\tshex:"+isConformant);
						basic_output.write(filename+"\t"+modelIRI+"\t"+isConsistent+"\t"+isConformant+"\n");
					}else {
						LOGGER.info(filename+"\t"+modelIRI+"\tOWL:"+isConsistent+"\tshex:not checked");
						basic_output.write(filename+"\t"+modelIRI+"\t"+isConsistent+"\tnot checked\n");						
					}
				} catch (InconsistentOntologyException e) {
					LOGGER.error("Inconsistent model: " + modelIRI);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} 
			});
			m3.dispose();
		}finally{
			basic_output.close();
		}
	}
	
	
	public static void printVersion() throws Exception {
		printManifestEntry("git-revision-sha1", "UNKNOWN");
		printManifestEntry("git-revision-url", "UNKNOWN");
		printManifestEntry("git-branch", "UNKNOWN");
		printManifestEntry("git-dirty", "UNKNOWN");
	}
	
	private static String printManifestEntry(String key, String defaultValue) {
		String value = owltools.version.VersionInfo.getManifestVersion(key);
		if (value == null || value.isEmpty()) {
			value = defaultValue;
		}
		System.out.println(key+"\t"+value);
		return value;
	}
	
}
