package org.geneontology.minerva.cli;

import com.bigdata.journal.Options;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;
import com.google.common.base.Optional;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.log4j.Logger;
import org.geneontology.minerva.BlazegraphMolecularModelManager;
import org.geneontology.minerva.GafToLegoIndividualTranslator;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.InferenceProvider;
import org.geneontology.minerva.json.JsonModel;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.legacy.GroupingTranslator;
import org.geneontology.minerva.legacy.LegoToGeneAnnotationTranslator;
import org.geneontology.minerva.legacy.sparql.GPADSPARQLExport;
import org.geneontology.minerva.lookup.ExternalLookupService;
import org.geneontology.minerva.util.BlazegraphMutationCounter;
import org.geneontology.minerva.validation.Enricher;
import org.geneontology.minerva.validation.ShexValidationReport;
import org.geneontology.minerva.validation.ShexValidator;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.UpdateExecutionException;
import org.openrdf.repository.RepositoryException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import owltools.cli.JsCommandRunner;
import owltools.cli.Opts;
import owltools.cli.tools.CLIMethod;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.gaf.eco.EcoMapperFactory;
import owltools.gaf.eco.SimpleEcoMapper;
import owltools.gaf.io.GafWriter;
import owltools.gaf.io.GpadWriter;
import owltools.graph.OWLGraphWrapper;
import uk.ac.manchester.cs.owlapi.modularity.ModuleType;
import uk.ac.manchester.cs.owlapi.modularity.SyntacticLocalityModuleExtractor;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

public class MinervaCommandRunner extends JsCommandRunner {

	private static final Logger LOGGER = Logger.getLogger(MinervaCommandRunner.class);

	@CLIMethod("--dump-owl-models")
	public void modelsToOWL(Opts opts) throws Exception {
		opts.info("[-j|--journal JOURNALFILE] [-f|--folder OWLFILESFOLDER] [-p|--prefix MODELIDPREFIX]",
				"dumps all LEGO models to OWL Turtle files");
		// parameters
		String journalFilePath = null;
		String outputFolder = null;
		String modelIdPrefix = "http://model.geneontology.org/";

		// parse opts
		while (opts.hasOpts()) {
			if (opts.nextEq("-j|--journal")) {
				opts.info("journal file", "Sets the Blazegraph journal file for the database");
				journalFilePath = opts.nextOpt();
			}
			else if (opts.nextEq("-f|--folder")) {
				opts.info("OWL folder", "Sets the output folder the LEGO model files");
				outputFolder = opts.nextOpt();
			}
			else if (opts.nextEq("-p|--prefix")) {
				opts.info("model ID prefix", "Sets the URI prefix for model IDs");
				modelIdPrefix = opts.nextOpt();
			}
			else {
				break;
			}
		}

		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			exit(-1);
			return;
		}
		if (outputFolder == null) {
			System.err.println("No output folder was configured.");
			exit(-1);
			return;
		}

		OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
		CurieHandler curieHandler = new MappedCurieHandler();
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, outputFolder);
		m3.dumpAllStoredModels();
		m3.dispose();
	}

	@CLIMethod("--import-owl-models")
	public void importOWLModels(Opts opts) throws Exception {
		opts.info("[-j|--journal JOURNALFILE] [-f|--folder OWLFILESFOLDER]",
				"import all files in folder to database");
		// parameters
		String journalFilePath = null;
		String inputFolder = null;

		// parse opts
		while (opts.hasOpts()) {
			if (opts.nextEq("-j|--journal")) {
				opts.info("journal file", "Sets the Blazegraph journal file for the database");
				journalFilePath = opts.nextOpt();
			}
			else if (opts.nextEq("-f|--folder")) {
				opts.info("OWL folder", "Sets the folder containing the LEGO model files");
				inputFolder = opts.nextOpt();
			}
			else {
				break;
			}
		}

		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			exit(-1);
			return;
		}
		if (inputFolder == null) {
			System.err.println("No input folder was configured.");
			exit(-1);
			return;
		}

		OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
		String modelIdPrefix = "http://model.geneontology.org/"; // this will not be used for anything
		CurieHandler curieHandler = new MappedCurieHandler();
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, null);
		for (File file : FileUtils.listFiles(new File(inputFolder), null, true)) {
			LOGGER.info("Loading " + file);
			m3.importModelToDatabase(file, true);
		}
		m3.dispose();
	}

	@CLIMethod("--sparql-update")
	public void sparqlUpdate(Opts opts) throws OWLOntologyCreationException, IOException, RepositoryException, MalformedQueryException, UpdateExecutionException {
		opts.info("[-j|--journal JOURNALFILE] [-f|--file SPARQL UPDATE FILE]",
				"apply SPARQL update to database");
		String journalFilePath = null;
		String updateFile = null;

		// parse opts
		while (opts.hasOpts()) {
			if (opts.nextEq("-j|--journal")) {
				opts.info("journal file", "Sets the Blazegraph journal file for the database");
				journalFilePath = opts.nextOpt();
			}
			else if (opts.nextEq("-f|--file")) {
				opts.info("OWL folder", "Sets the file containing a SPARQL update");
				updateFile = opts.nextOpt();
			}
			else {
				break;
			}
		}

		// minimal inputs
		if (journalFilePath == null) {
			System.err.println("No journal file was configured.");
			exit(-1);
			return;
		}
		if (updateFile == null) {
			System.err.println("No update file was configured.");
			exit(-1);
			return;
		}

		String update = FileUtils.readFileToString(new File(updateFile), StandardCharsets.UTF_8);
		Properties properties = new Properties();
		properties.load(this.getClass().getResourceAsStream("/org/geneontology/minerva/blazegraph.properties"));
		properties.setProperty(Options.FILE, journalFilePath);
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

	@CLIMethod("--owl-lego-to-json")
	public void owl2LegoJson(Opts opts) throws Exception {
		opts.info("[-o JSONFILE] [-i OWLFILE] [--pretty-json] [--compact-json]",
				"converts the LEGO subset of OWL to Minerva-JSON");
		// parameters
		String input = null;
		String output = null;
		boolean usePretty = true;

		// parse opts
		while (opts.hasOpts()) {
			if (opts.nextEq("-o|--output")) {
				opts.info("output", "Sets the output file for the json");
				output = opts.nextOpt();
			}
			else if (opts.nextEq("-i|--input")) {
				opts.info("input", "Sets the input file for the model");
				input = opts.nextOpt();
			}
			else if (opts.nextEq("--pretty-json")) {
				opts.info("", "pretty print the output json");
				usePretty = true;
			}
			else if (opts.nextEq("--compact-json")) {
				opts.info("", "compact print the output json");
				usePretty = false;
			}
			else {
				break;
			}
		}

		// minimal inputs
		if (input == null) {
			System.err.println("No input model was configured.");
			exit(-1);
			return;
		}
		if (output == null) {
			System.err.println("No output file was configured.");
			exit(-1);
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
	 * Translate the GeneAnnotations into a lego all individual OWL representation.
	 * 
	 * Will merge the source ontology into the graph by default
	 * 
	 * @param opts
	 * @throws Exception
	 */
	@CLIMethod("--gaf-lego-individuals")
	public void gaf2LegoIndivduals(Opts opts) throws Exception {
		boolean addLineNumber = false;
		boolean merge = true;
		boolean minimize = false;
		String output = null;
		CurieHandler curieHandler = DefaultCurieHandler.getDefaultHandler();
		OWLDocumentFormat format = new RDFXMLDocumentFormat();
		while (opts.hasOpts()) {
			if (opts.nextEq("-o|--output")) {
				output = opts.nextOpt();
			}
			else if (opts.nextEq("--format")) {
				String formatString = opts.nextOpt();
				if ("manchester".equalsIgnoreCase(formatString)) {
					format = new ManchesterSyntaxDocumentFormat();
				} else if ("turtle".equalsIgnoreCase(formatString)) {
					format = new TurtleDocumentFormat();
				}
				else if ("functional".equalsIgnoreCase(formatString)) {
					format = new FunctionalSyntaxDocumentFormat();
				}
			}
			else if (opts.nextEq("--add-line-number")) {
				addLineNumber = true;
			}
			else if (opts.nextEq("--skip-merge")) {
				merge = false;
			}
			else if (opts.nextEq("-m|--minimize")) {
				opts.info("", "use module extraction to include module of ontology");
				minimize = true;
			}
			else {
				break;
			}
		}
		if (g != null && gafdoc != null && output != null) {
			GafToLegoIndividualTranslator tr = new GafToLegoIndividualTranslator(g, curieHandler, addLineNumber);
			OWLOntology lego = tr.translate(gafdoc);

			if (merge) {
				new OWLGraphWrapper(lego).mergeImportClosure(true);	
			}
			if (minimize) {
				final OWLOntologyManager m = lego.getOWLOntologyManager();

				SyntacticLocalityModuleExtractor sme = new SyntacticLocalityModuleExtractor(m, lego, ModuleType.BOT);
				Set<OWLEntity> sig = new HashSet<OWLEntity>(lego.getIndividualsInSignature());
				Set<OWLAxiom> moduleAxioms = sme.extract(sig);

				OWLOntology module = m.createOntology(IRI.generateDocumentIRI());
				m.addAxioms(module, moduleAxioms);
				lego = module;
			}

			OWLOntologyManager manager = lego.getOWLOntologyManager();
			OutputStream outputStream = null;
			try {
				outputStream = new FileOutputStream(output);
				manager.saveOntology(lego, format, outputStream);
			}
			finally {
				IOUtils.closeQuietly(outputStream);
			}
		}
		else {
			if (output == null) {
				System.err.println("No output file was specified.");
			}
			if (g == null) {
				System.err.println("No graph available for gaf-run-check.");
			}
			if (gafdoc == null) {
				System.err.println("No loaded gaf available for gaf-run-check.");
			}
			exit(-1);
			return;
		}
	}

	/**
	 * Will test a go-cam model or directory of models against a shex schema and shape map to test conformance
	 * Example invocation: --validate-go-cams -s /Users/bgood/Documents/GitHub/GO_Shapes/shapes/go-cam-shapes.shex -m /Users/bgood/Documents/GitHub/GO_Shapes/shapes/go-cam-shapes.shapeMap -f /Users/bgood/Documents/GitHub/GO_Shapes/test_ttl/go_cams/should_pass/ -e -r /Users/bgood/Desktop/shapely_report.txt
	 * @param opts
	 * @throws Exception
	 */
	@CLIMethod("--validate-go-cams")
	public void validateGoCams(Opts opts) throws Exception {
		String url_for_tbox = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
		ShexValidator validator = null;
		String shexpath = null;//"../shapes/go-cam-shapes.shex";
		String shapemappath = null;
		String model_file = null;//"../test_ttl/go_cams/should_pass/typed_reactome-homosapiens-Acetylation.ttl";
		String report_file = null;
		boolean addSuperClasses = false;
		boolean addSuperClassesLocal = false;
		boolean travisMode = false; 
		boolean shouldPass = true;
		String extra_endpoint = null;
		Map<String, Model> name_model = new HashMap<String, Model>();

		while (opts.hasOpts()) {
			if(opts.nextEq("-t|--travis")) {
				travisMode = true;
			}
			else if (opts.nextEq("-f|--file")) {
				model_file = opts.nextOpt();
				name_model = Enricher.loadRDF(model_file);
			}
			else if (opts.nextEq("-shouldfail")) {
				shouldPass = false;
			}
			else if (opts.nextEq("-s|--shexpath")) {
				shexpath = opts.nextOpt();
			}
			else if(opts.nextEq("-m|--shapemap")) { 
				shapemappath = opts.nextOpt();
			}
			else if(opts.nextEq("-r|--report")) { 
				report_file = opts.nextOpt();
			}
			else if(opts.nextEq("-e|--expand")) { 
				addSuperClasses = true;
			}
			else if(opts.nextEq("-el|--elocal")) { 
				addSuperClassesLocal = true;
			}
			else if(opts.nextEq("-extra_endpoint")) { 
				extra_endpoint = opts.nextOpt();
			}else {
				break;
			}
		}
		//requirements
		if(model_file==null) {
			System.err.println("-f .No go-cam file or directory provided to validate.");
			exit(-1);
		}
		else if(shexpath==null) {
			System.err.println("-s .No shex schema provided.");
			exit(-1);
		}else if(shapemappath==null) {
			System.err.println("-m .No shape map file provided.");
			exit(-1);
		}else if(report_file==null) {
			System.err.println("-r .No report file specified");
			exit(-1);
		}
		else {
			validator = new ShexValidator(shexpath, shapemappath);
			FileWriter w = new FileWriter(report_file);
			int good = 0; int bad = 0;
			Enricher enrich = new Enricher(extra_endpoint, null);
			if(addSuperClassesLocal) {
				URL tbox_location = new URL(url_for_tbox);
				File tbox_file = new File("./target/go-lego.owl");
				System.out.println("downloading tbox ontology from "+url_for_tbox);
				org.apache.commons.io.FileUtils.copyURLToFile(tbox_location, tbox_file);
				System.out.println("loading tbox ontology from "+tbox_file.getAbsolutePath());
				OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();					
				OWLOntology tbox = ontman.loadOntologyFromOntologyDocument(tbox_file);
				System.out.println("done loading, building structural reasoner");
				OWLReasonerFactory reasonerFactory = new StructuralReasonerFactory();
				OWLReasoner tbox_reasoner = reasonerFactory.createReasoner(tbox);
				enrich = new Enricher(null, tbox_reasoner);
			}
			for(String name : name_model.keySet()) {
				Model test_model = name_model.get(name);
				if(addSuperClasses||addSuperClassesLocal) {
					test_model = enrich.enrichSuperClasses(test_model);
				}
				if(validator.GoQueryMap!=null){
					boolean stream_output = true;
					ShexValidationReport r = validator.runShapeMapValidation(test_model, stream_output);
					System.out.println(name+" conformant:"+r.isConformant());
					w.write(name+"\t");
					if(!r.isConformant()) {
						w.write("invalid\n");
						bad++;
						if(travisMode&&(shouldPass)) {
							System.out.println(name+" should have validated but did not "+r.getAsText());
							System.exit(-1);
						}
					}else {
						good++;
						w.write("valid\n");
						if(travisMode&&(!shouldPass)) {
							System.out.println(name+" should NOT have validated but did ");
							System.exit(-1);
						}
					}
				}
			}
			w.close();
			System.out.println("input: "+model_file+" total:"+name_model.size()+" Good:"+good+" Bad:"+bad);
		}
	}
	/**
	 * Output GPAD files via inference+SPARQL, for testing only
	 * @param opts
	 * @throws Exception
	 */
	@CLIMethod("--lego-to-gpad-sparql")
	public void legoToAnnotationsSPARQL(Opts opts) throws Exception {
		String modelIdPrefix = "http://model.geneontology.org/";
		String modelIdcurie = "gomodel";
		String inputDB = "blazegraph.jnl";
		String gpadOutputFolder = null;
		String ontologyIRI = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
		while (opts.hasOpts()) {
			if (opts.nextEq("-i|--input")) {
				inputDB = opts.nextOpt();
			}
			else if (opts.nextEq("--gpad-output")) {
				gpadOutputFolder = opts.nextOpt();
			}
			else if (opts.nextEq("--model-id-prefix")) {
				modelIdPrefix = opts.nextOpt();
			}
			else if (opts.nextEq("--model-id-curie")) {
				modelIdcurie = opts.nextOpt();
			}
			else if (opts.nextEq("--ontology")) {
				ontologyIRI = opts.nextOpt();
			}
			else {
				break;
			}
		}
		OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(ontologyIRI));
		CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
		CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
		BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(ontology, curieHandler, modelIdPrefix, inputDB, null);
		for (IRI modelIRI : m3.getAvailableModelIds()) {
			try {
				String gpad = new GPADSPARQLExport(curieHandler, m3.getLegacyRelationShorthandIndex(), m3.getTboxShorthandIndex(), m3.getDoNotAnnotateSubset()).exportGPAD(m3.createCanonicalInferredModel(modelIRI));
				String fileName = StringUtils.replaceOnce(modelIRI.toString(), modelIdPrefix, "") + ".gpad";
				Writer writer = new OutputStreamWriter(new FileOutputStream(Paths.get(gpadOutputFolder, fileName).toFile()), StandardCharsets.UTF_8);
				writer.write(gpad);
				writer.close();	
			} catch (InconsistentOntologyException e) {
				LOGGER.error("Inconsistent ontology: " + modelIRI);
			}
		}
		m3.dispose();
	}

	@CLIMethod("--lego-to-gpad")
	public void legoToAnnotations(Opts opts) throws Exception {
		String modelIdPrefix = "http://model.geneontology.org/";
		String modelIdcurie = "gomodel";
		String inputFolder = null;
		String singleFile = null;
		String gpadOutputFolder = null;
		String gafOutputFolder = null;
		Map<String, String> taxonGroups = null;
		String defaultTaxonGroup = "other";
		boolean addLegoModelId = true;
		String fileSuffix = "ttl";
		while (opts.hasOpts()) {
			if (opts.nextEq("-i|--input")) {
				inputFolder = opts.nextOpt();
			}
			else if (opts.nextEq("--input-single-file")) {
				singleFile = opts.nextOpt();
			}
			else if (opts.nextEq("--gpad-output")) {
				gpadOutputFolder = opts.nextOpt();
			}
			else if (opts.nextEq("--gaf-output")) {
				gafOutputFolder = opts.nextOpt();
			}
			else if (opts.nextEq("--remove-lego-model-ids")) {
				addLegoModelId = false;
			}
			else if (opts.nextEq("--model-id-prefix")) {
				modelIdPrefix = opts.nextOpt();
			}
			else if (opts.nextEq("-s|--input-file-suffix")) {
				opts.info("SUFFIX", "if a directory is specified, use only files with this suffix. Default is 'ttl'");
				fileSuffix = opts.nextOpt();
			}
			else if (opts.nextEq("--model-id-curie")) {
				modelIdcurie = opts.nextOpt();
			}
			else if (opts.nextEq("--group-by-model-organisms")) {
				if (taxonGroups == null) {
					taxonGroups = new HashMap<>();
				}
				// get the pre-defined groups from the model-organism-groups.tsv resource
				List<String> lines = IOUtils.readLines(MinervaCommandRunner.class.getResourceAsStream("/model-organism-groups.tsv"));
				for (String line : lines) {
					line = StringUtils.trimToEmpty(line);
					if (line.isEmpty() == false && line.charAt(0) != '#') {
						String[] split = StringUtils.splitByWholeSeparator(line, "\t");
						if (split.length > 1) {
							String group = split[0];
							Set<String> taxonIds = new HashSet<>();
							for (int i = 1; i < split.length; i++) {
								taxonIds.add(split[i]);
							}
							for(String taxonId : taxonIds) {
								taxonGroups.put(taxonId, group);
							}
						}
					}

				}
			}
			else if (opts.nextEq("--add-model-organism-group")) {
				if (taxonGroups == null) {
					taxonGroups = new HashMap<>();
				}
				String group = opts.nextOpt();
				String taxon = opts.nextOpt();
				taxonGroups.put(taxon, group);
			}
			else if (opts.nextEq("--set-default-model-group")) {
				defaultTaxonGroup = opts.nextOpt();
			}
			else {
				break;
			}
		}
		// create curie handler
		CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
		CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);

		ExternalLookupService lookup= null;

		SimpleEcoMapper mapper = EcoMapperFactory.createSimple();
		LegoToGeneAnnotationTranslator translator = new LegoToGeneAnnotationTranslator(g.getSourceOntology(), curieHandler, mapper);
		GroupingTranslator groupingTranslator = new GroupingTranslator(translator, lookup, taxonGroups, defaultTaxonGroup, addLegoModelId);

		final OWLOntologyManager m = g.getManager();
		if (singleFile != null) {
			File file = new File(singleFile).getCanonicalFile();
			OWLOntology model = m.loadOntology(IRI.create(file));
			groupingTranslator.translate(model);
		}
		else if (inputFolder != null) {
			final String fileSuffixFinal = fileSuffix;
			File inputFile = new File(inputFolder).getCanonicalFile();
			if (inputFile.isDirectory()) {
				File[] files = inputFile.listFiles(new FilenameFilter() {

					@Override
					public boolean accept(File dir, String name) {
						if (fileSuffixFinal != null && fileSuffixFinal.length() > 0)
							return name.endsWith("."+fileSuffixFinal);
						else
							return StringUtils.isAlphanumeric(name);
					}
				});
				for (File file : files) {
					OWLOntology model = m.loadOntology(IRI.create(file));
					groupingTranslator.translate(model);
				}
			}
		}

		// by state
		for(String modelState : groupingTranslator.getModelStates()) {
			GafDocument annotations = groupingTranslator.getAnnotationsByState(modelState);
			sortAnnotations(annotations);
			if (annotations != null) {
				if (gpadOutputFolder != null) {
					writeGpad(annotations, gpadOutputFolder, "all-"+modelState);
				}
				if (gafOutputFolder != null) {
					writeGaf(annotations, gafOutputFolder, "all-"+modelState);
				}
			}
		}

		// by organism
		if (taxonGroups != null) {
			for(String group : groupingTranslator.getTaxonGroups()) {
				GafDocument filtered = groupingTranslator.getAnnotationsByGroup(group);
				sortAnnotations(filtered);
				if (gpadOutputFolder != null) {
					writeGpad(filtered, gpadOutputFolder, group);
				}
				if (gafOutputFolder != null) {
					writeGaf(filtered, gafOutputFolder, group);
				}
			}
		}

		// production only by organism
		if (taxonGroups != null) {
			for(String group : groupingTranslator.getProductionTaxonGroups()) {
				GafDocument filtered = groupingTranslator.getProductionAnnotationsByGroup(group);
				sortAnnotations(filtered);
				if (gpadOutputFolder != null) {
					String productionFolder = new File(gpadOutputFolder, "production").getAbsolutePath();
					writeGpad(filtered, productionFolder+"", group);
				}
				if (gafOutputFolder != null) {
					String productionFolder = new File(gafOutputFolder, "production").getAbsolutePath();
					writeGaf(filtered, productionFolder, group);
				}
			}
		}
	}

	static void sortAnnotations(GafDocument annotations) {
		Collections.sort(annotations.getGeneAnnotations(), new Comparator<GeneAnnotation>() {
			@Override
			public int compare(GeneAnnotation a1, GeneAnnotation a2) {
				return a1.toString().compareTo(a2.toString());
			}
		});
	}

	private void writeGaf(GafDocument annotations, String outputFolder, String fileName) {
		File outputFile = new File(outputFolder, fileName+".gaf");
		GafWriter writer = new GafWriter();
		try {
			outputFile.getParentFile().mkdirs();
			writer.setStream(outputFile);
			writer.write(annotations);
		}
		finally {
			IOUtils.closeQuietly(writer);	
		}
	}

	private void writeGpad(GafDocument annotations, String outputFolder, String fileName) throws FileNotFoundException {
		PrintWriter fileWriter = null;
		File outputFile = new File(outputFolder, fileName+".gpad");
		try {
			outputFile.getParentFile().mkdirs();
			fileWriter = new PrintWriter(outputFile);
			GpadWriter writer = new GpadWriter(fileWriter, 1.2d);
			writer.write(annotations);
		}
		finally {
			IOUtils.closeQuietly(fileWriter);	
		}
	}
}
