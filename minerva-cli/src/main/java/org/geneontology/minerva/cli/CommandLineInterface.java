package org.geneontology.minerva.cli;


import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;
import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.geneontology.minerva.*;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.InferenceProvider;
import org.geneontology.minerva.json.JsonModel;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.legacy.sparql.GPADData;
import org.geneontology.minerva.legacy.sparql.GPADSPARQLExport;
import org.geneontology.minerva.model.ActivityUnit;
import org.geneontology.minerva.model.GoCamModel;
import org.geneontology.minerva.model.GoCamModelStats;
import org.geneontology.minerva.server.StartUpTool;
import org.geneontology.minerva.server.handler.OperationsTools;
import org.geneontology.minerva.server.inferences.InferenceProviderCreator;
import org.geneontology.minerva.server.validation.MinervaShexValidator;
import org.geneontology.minerva.util.BlazegraphMutationCounter;
import org.geneontology.minerva.validation.ShexValidationReport;
import org.geneontology.minerva.validation.ValidationResultSet;
import org.geneontology.minerva.validation.Violation;
import org.geneontology.minerva.validation.pipeline.BatchPipelineValidationReport;
import org.geneontology.minerva.validation.pipeline.ErrorMessage;
import org.obolibrary.robot.CatalogXmlIRIMapper;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.OWL;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.UpdateExecutionException;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import owltools.io.ParserWrapper;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;

import static org.geneontology.minerva.server.handler.OperationsTools.createModelRenderer;


public class CommandLineInterface {
    private static final Logger LOGGER = Logger.getLogger(CommandLineInterface.class);

    public static void main(String[] args) {

        reportSystemParams();
        Options main_options = new Options();
        OptionGroup methods = new OptionGroup();
        methods.setRequired(true);
        Option dumpJSON = Option.builder()
                .longOpt("dump-owl-json")
                .desc("export JSON format GO-CAM models from journal")
                .hasArg(false)
                .build();
        methods.addOption(dumpJSON);
        Option dump = Option.builder()
                .longOpt("dump-owl-models")
                .desc("export OWL GO-CAM models from journal")
                .hasArg(false)
                .build();
        methods.addOption(dump);
        Option merge_ontologies = Option.builder()
                .longOpt("merge-ontologies")
                .desc("Merge owl ontologies")
                .hasArg(false)
                .build();
        methods.addOption(merge_ontologies);
        Option import_owl = Option.builder()
                .longOpt("import-owl-models")
                .desc("import OWL GO-CAM models into journal")
                .hasArg(false)
                .build();
        methods.addOption(import_owl);
        Option import_tbox_ontologies = Option.builder()
                .longOpt("import-tbox-ontologies")
                .desc("import OWL tbox ontologies into journal")
                .hasArg(false)
                .build();
        methods.addOption(import_tbox_ontologies);
        Option add_taxon_metadata = Option.builder()
                .longOpt("add-taxon-metadata")
                .desc("add taxon associated with genes in each model as an annotation on the model")
                .hasArg(false)
                .build();
        methods.addOption(add_taxon_metadata);
        Option clean_gocams = Option.builder()
                .longOpt("clean-gocams")
                .desc("remove import statements, add property declarations, remove json-model annotation")
                .hasArg(false)
                .build();
        methods.addOption(clean_gocams);
        Option sparql = Option.builder()
                .longOpt("sparql-update")
                .desc("update the blazegraph journal with the given sparql statement")
                .hasArg(false)
                .build();
        methods.addOption(sparql);
        Option replaceObsolete = Option.builder()
                .longOpt("replace-obsolete")
                .desc("replace references to obsolete terms with their replaced_by values")
                .hasArg(false)
                .build();
        methods.addOption(replaceObsolete);
        Option replaceTerms = Option.builder()
                .longOpt("replace-terms")
                .desc("replace specified term usages with replacement values")
                .hasArg(false)
                .build();
        methods.addOption(replaceTerms);
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
        Option validate = Option.builder()
                .longOpt("validate-go-cams")
                .desc("Check a collection of go-cam files or a journal for valid semantics (owl) and structure (shex)")
                .hasArg(false)
                .build();
        methods.addOption(validate);

        main_options.addOptionGroup(methods);

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(main_options, args, true);

            if (cmd.hasOption("add-taxon-metadata")) {
                Options add_taxon_options = new Options();
                add_taxon_options.addOption(add_taxon_metadata);
                add_taxon_options.addOption("j", "journal", true, "This is the go-cam journal that will be updated with taxon annotations.");
                add_taxon_options.addOption("ontojournal", "ontojournal", true, "Specify a blazegraph journal file containing the merged, pre-reasoned tbox aka go-lego.owl");
                cmd = parser.parse(add_taxon_options, args, false);
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String ontojournal = cmd.getOptionValue("ontojournal"); //--folder
                addTaxonMetaData(journalFilePath, ontojournal);
            }

            if (cmd.hasOption("clean-gocams")) {
                Options clean_options = new Options();
                clean_options.addOption(clean_gocams);
                clean_options.addOption("i", "input", true, "This is the directory of gocam files to clean.");
                clean_options.addOption("o", "output", true, "This is the directory of cleaned gocam files that are produced.");
                cmd = parser.parse(clean_options, args, false);
                cleanGoCams(cmd.getOptionValue("i"), cmd.getOptionValue("o"));
            }

            if (cmd.hasOption("import-tbox-ontologies")) {
                Options import_tbox_options = new Options();
                import_tbox_options.addOption(import_tbox_ontologies);
                import_tbox_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                import_tbox_options.addOption("f", "file", true, "Sets the input file containing the ontology to load");
                import_tbox_options.addOption("r", "reset", false, "If present, will clear out the journal, otherwise adds to it");
                cmd = parser.parse(import_tbox_options, args, false);
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String inputFile = cmd.getOptionValue("f"); //--folder
                importOWLOntologyIntoJournal(journalFilePath, inputFile, cmd.hasOption("r"));
            }
            if (cmd.hasOption("merge-ontologies")) {
                Options merge_options = new Options();
                merge_options.addOption(merge_ontologies);
                merge_options.addOption("i", "input", true, "The input folder containing ontologies to merge");
                merge_options.addOption("o", "output", true, "The file to write the ontology to");
                merge_options.addOption("u", "iri", true, "The base iri for the merged ontology");
                merge_options.addOption("r", "reason", false, "Add inferences to the merged ontology");
                cmd = parser.parse(merge_options, args, false);
                buildMergedOwlOntology(cmd.getOptionValue("i"), cmd.getOptionValue("o"), cmd.getOptionValue("u"), cmd.hasOption("r"));
            }

            if (cmd.hasOption("dump-owl-models")) {
                Options dump_options = new Options();
                dump_options.addOption(dump);
                dump_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                dump_options.addOption("f", "folder", true, "Sets the output folder the GO-CAM model files");
                dump_options.addOption("p", "model-id-prefix", true, "prefix for GO-CAM model ids");
                cmd = parser.parse(dump_options, args, false);
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String outputFolder = cmd.getOptionValue("f"); //--folder
                String modelIdPrefix = cmd.getOptionValue("p"); //--prefix
                modelsToOWL(journalFilePath, outputFolder, modelIdPrefix);
            } else if (cmd.hasOption("dump-owl-json")) {
                Options jsonDumpOptions = new Options();
                jsonDumpOptions.addOption(dumpJSON);
                jsonDumpOptions.addOption("g", "ontology", true, "Specify ontology file URL");
                jsonDumpOptions.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                jsonDumpOptions.addOption("ontojournal", "ontojournal", true, "Specify a blazegraph journal file containing the merged, pre-reasoned tbox aka go-lego.owl");
                jsonDumpOptions.addOption("f", "folder", true, "Sets the output folder the GO-CAM model files");
                jsonDumpOptions.addOption("p", "model-id-prefix", true, "prefix for GO-CAM model ids");
                jsonDumpOptions.addOption("prefixes", "prefixes", true, "Prefix mappings file");
                cmd = parser.parse(jsonDumpOptions, args, false);
                String ontologyFileURL = cmd.getOptionValue("g");
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String ontojournalFilePath = cmd.getOptionValue("ontojournal");
                String outputFolder = cmd.getOptionValue("f"); //--folder
                String modelIdPrefix = cmd.getOptionValue("p"); //--prefix
                String prefixMappingsFileLoc = cmd.getOptionValue("prefixes");
                modelsToJSON(ontologyFileURL, journalFilePath, ontojournalFilePath, outputFolder, modelIdPrefix, prefixMappingsFileLoc);
            } else if (cmd.hasOption("import-owl-models")) {
                Options import_options = new Options();
                import_options.addOption(import_owl);
                import_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                import_options.addOption("f", "folder", true, "Sets the input folder the GO-CAM model files");
                cmd = parser.parse(import_options, args, false);
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String outputFolder = cmd.getOptionValue("f"); //--folder
                importOWLModels(journalFilePath, outputFolder);
            } else if (cmd.hasOption("sparql-update")) {
                Options sparql_options = new Options();
                sparql_options.addOption(sparql);
                sparql_options.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                sparql_options.addOption("f", "file", true, "Sets the file containing a SPARQL update");
                cmd = parser.parse(sparql_options, args, false);
                String journalFilePath = cmd.getOptionValue("j"); //--journal
                String file = cmd.getOptionValue("f");
                sparqlUpdate(journalFilePath, file);
            } else if (cmd.hasOption("replace-obsolete")) {
                Options replaceOptions = new Options();
                replaceOptions.addOption(replaceObsolete);
                replaceOptions.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                replaceOptions.addOption("ont", "ontology", true, "IRI of tbox ontology for classification - usually default go-lego.owl");
                replaceOptions.addOption("cat", "catalog", true, "Catalog file for tbox ontology. " +
                        "Use this to specify local copies of the ontology and or its imports to " +
                        "speed and control the process. If not used, will download the tbox and all its imports.");
                cmd = parser.parse(replaceOptions, args, false);
                String journalFilePath = cmd.getOptionValue("j");
                String ontologyIRI = cmd.getOptionValue("ontology");
                String catalogPath = cmd.getOptionValue("catalog");
                ReplaceObsoleteReferencesCommand.run(ontologyIRI, catalogPath, journalFilePath);
            } else if (cmd.hasOption("replace-terms")) {
                Options replaceTermsOptions = new Options();
                replaceTermsOptions.addOption(replaceTerms);
                replaceTermsOptions.addOption("j", "journal", true, "Sets the Blazegraph journal file for the database");
                replaceTermsOptions.addOption("c", "replacement-classes", true, "TSV containing replacement class mappings");
                replaceTermsOptions.addOption("p", "replacement-properties", true, "TSV containing replacement object property mappings");
                cmd = parser.parse(replaceTermsOptions, args, false);
                String journalFilePath = cmd.getOptionValue("j");
                String replacementClassesPath = cmd.getOptionValue("replacement-classes");
                String replacementObjectPropertiesPath = cmd.getOptionValue("replacement-properties");
                ReplaceTermsCommand.run(journalFilePath, replacementClassesPath, replacementObjectPropertiesPath);
            } else if (cmd.hasOption("owl-lego-to-json")) {
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
                cmd = parser.parse(json_options, args, false);
                String input = cmd.getOptionValue("i");
                String output = cmd.getOptionValue("o");
                boolean usePretty = true;
                if (cmd.hasOption("compact-json")) {
                    usePretty = false;
                }
                owl2LegoJson(input, output, usePretty);
            } else if (cmd.hasOption("lego-to-gpad-sparql")) {
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
                gpad_options.addOption("ontojournal", "ontojournal", true, "Specify a blazegraph journal file containing the merged, pre-reasoned tbox aka go-lego.owl");
                cmd = parser.parse(gpad_options, args, false);
                String inputDB = cmd.getOptionValue("input");
                String gpadOutputFolder = cmd.getOptionValue("gpad-output");
                String modelIdPrefix = cmd.getOptionValue("model-id-prefix");
                String modelIdcurie = cmd.getOptionValue("model-id-curie");
                String ontologyIRI = cmd.getOptionValue("ontology");
                String catalog = cmd.getOptionValue("catalog");
                String go_lego_journal_file = null;
                if (cmd.hasOption("ontojournal")) {
                    go_lego_journal_file = cmd.getOptionValue("ontojournal");
                }
                if (go_lego_journal_file == null) {
                    System.err.println("Missing -- ontojournal .  Need to specify location for blazegraph journal file containing the merged go-lego tbox (neo, GO-plus, etc..). If a journal does not exist at that location, the tbox ontology will be used to initialize one.");
                    System.exit(-1);
                }
                legoToAnnotationsSPARQL(modelIdPrefix, modelIdcurie, inputDB, gpadOutputFolder, ontologyIRI, catalog, go_lego_journal_file);
            } else if (cmd.hasOption("version")) {
                printVersion();
            } else if (cmd.hasOption("validate-go-cams")) {
                Options validate_options = new Options();
                validate_options.addOption(validate);
                validate_options.addOption("i", "input", true, "Either a blazegraph journal or a folder with go-cams in it");
                validate_options.addOption("shex", "shex", false, "If present, will execute shex validation");
                validate_options.addOption("owl", "owl", false, "If present, will execute shex validation");
                validate_options.addOption("r", "report-folder", true, "Folder where output files will appear");
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
                validate_options.addOption("ontojournal", "ontojournal", true, "Specify a blazegraph journal file containing the merged, pre-reasoned tbox aka go-lego.owl");
                validate_options.addOption("reasoner_report", "reasoner_report", false, "Add a report with reasoning results to the output of the validation. ");
                validate_options.addOption("graph", "check-graph-type", false, "Run the checks agains a GO graphstore journal; check only graphs with graphType noctuaCam.");


                cmd = parser.parse(validate_options, args, false);
                String input = cmd.getOptionValue("input");
                String outputFolder = cmd.getOptionValue("report-folder");
                String shexpath = cmd.getOptionValue("s");
                String shapemappath = cmd.getOptionValue("shapemap");

                String ontologyIRI = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
                if (cmd.hasOption("ontology")) {
                    ontologyIRI = cmd.getOptionValue("ontology");
                }
                String catalog = cmd.getOptionValue("catalog");
                String modelIdPrefix = "http://model.geneontology.org/";
                if (cmd.hasOption("model-id-prefix")) {
                    modelIdPrefix = cmd.getOptionValue("model-id-prefix");
                }
                String modelIdcurie = "gomodel";
                if (cmd.hasOption("model-id-curie")) {
                    modelIdcurie = cmd.getOptionValue("model-id-curie");
                }
                boolean travisMode = false;
                if (cmd.hasOption("travis")) {
                    travisMode = true;
                }
                boolean shouldFail = false;
                if (cmd.hasOption("shouldfail")) {
                    shouldFail = true;
                }
                boolean checkShex = false;
                if (cmd.hasOption("shex")) {
                    checkShex = true;
                }
                String go_lego_journal_file = null;
                if (cmd.hasOption("ontojournal")) {
                    go_lego_journal_file = cmd.getOptionValue("ontojournal");
                }
                if (go_lego_journal_file == null) {
                    System.err.println("Missing -- ontojournal .  Need to specify blazegraph journal file containing the merged go-lego tbox (neo, GO-plus, etc..)");
                    System.exit(-1);
                }
                boolean run_reasoner_report = false;
                if (cmd.hasOption("reasoner_report")) {
                    run_reasoner_report = true;
                }
                boolean checkGraphType = false;
                if (cmd.hasOption("check-graph-type")) {
                    checkGraphType = true;
                }
                validateGoCams(input, outputFolder, ontologyIRI, catalog, modelIdPrefix, modelIdcurie, shexpath, shapemappath, travisMode, shouldFail, checkShex, go_lego_journal_file, run_reasoner_report, checkGraphType);
            }
        } catch (ParseException exp) {
            System.out.println("Parameter parse exception.  Note that the first parameter must be one of: "
                    + "[--validate-go-cams, --dump-owl-models, --import-owl-models, --sparql-update, --owl-lego-to-json, --lego-to-gpad-sparql, --version, --update-gene-product-types]"
                    + "\nSubsequent parameters are specific to each top level command. "
                    + "\nError message: " + exp.getMessage());
            System.exit(-1);
        } catch (Exception e) {
            e.printStackTrace();
            //explicitly exiting to inform travis of failure.
            System.exit(-1);
        }
    }

    /**
     * Given a blazegraph journal with go-cams in it, write them all out as OWL files.
     * cli --dump-owl-models
     *
     * @param journalFilePath
     * @param outputFolder
     * @param modelIdPrefix
     * @throws Exception
     */
    public static void modelsToOWL(String journalFilePath, String outputFolder, String modelIdPrefix) throws Exception {
        if (modelIdPrefix == null) {
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
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, outputFolder, null, false);
        m3.dumpAllStoredModels();
        m3.dispose();
    }

    /**
     * Given a blazegraph journal with go-cams in it, write them all out as JSON files.
     * cli --dump-owl-json
     *
     * @param journalFilePath
     * @param outputFolder
     * @param modelIdPrefix
     * @throws Exception
     */
    public static void modelsToJSON(String ontologyFileURL, String journalFilePath, String ontojournalFilePath, String outputFolder, String modelIdPrefix, String prefixMappingsFilePath) throws Exception {
        final String idPrefix;
        if (modelIdPrefix == null) {
            idPrefix = "http://model.geneontology.org/";
        } else {
            idPrefix = modelIdPrefix;
        }
        // minimal inputs
        if (journalFilePath == null) {
            System.err.println("No journal file was configured.");
            System.exit(-1);
            return;
        }
        if (ontojournalFilePath == null) {
            System.err.println("No ontology journal file was configured.");
            System.exit(-1);
            return;
        }
        if (outputFolder == null) {
            System.err.println("No output folder was configured.");
            System.exit(-1);
            return;
        }
        final CurieMappings mappings;
        if (prefixMappingsFilePath != null) {
            mappings = DefaultCurieHandler.loadMappingsFromFile(new File(prefixMappingsFilePath));
        } else {
            mappings = DefaultCurieHandler.loadDefaultMappings();
        }
        CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap("gomodel", idPrefix));
        CurieHandler curieHandler = new MappedCurieHandler(mappings, localMappings);
        final OWLOntology ontology;
        if (ontologyFileURL == null) {
            ontology = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
        } else {
            ontology = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(ontologyFileURL));
        }
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(ontology, curieHandler, idPrefix, journalFilePath, outputFolder, ontojournalFilePath, true);
        InferenceProvider inferenceProvider = null;
        Gson gson = new Gson();
        FileUtils.forceMkdir(new File(outputFolder));
        m3.getStoredModelIds().forEach(iri -> {
            ModelContainer mc = m3.getModel(iri);
            final MolecularModelJsonRenderer renderer = OperationsTools.createModelRenderer(mc, m3.getGolego_repo(), inferenceProvider, curieHandler, m3.getTboxLabelIndex());
            JsonModel jsonModel = renderer.renderModel();
            String fileName = StringUtils.replaceOnce(iri.toString(), idPrefix, "") + ".json";
            File targetFile = new File(outputFolder, fileName).getAbsoluteFile();
            if (targetFile.exists()) {
                if (!targetFile.isFile()) {
                    throw new RuntimeException(new IOException("For modelId: '" + iri + "', the resulting path is not a file: " + targetFile.getAbsolutePath()));
                }
                if (!targetFile.canWrite()) {
                    throw new RuntimeException(new IOException("For modelId: '" + iri + "', Cannot write to the file: " + targetFile.getAbsolutePath()));
                }
            }
            File tempFile = null;
            try {
                // create tempFile
                String prefix = iri.toString(); // TODO escape
                tempFile = File.createTempFile(prefix, ".json");
                try (FileWriter writer = new FileWriter(tempFile)) {
                    gson.toJson(jsonModel, writer);
                }
                FileUtils.copyFile(tempFile, targetFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                // delete temp file
                FileUtils.deleteQuietly(tempFile);
            }
            m3.unlinkModel(iri);
        });
        m3.dispose();
    }

    /**
     * Load the go-cam files in the input folder into the journal
     * cli import-owl-models
     *
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
        int total_files = 0;
        OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
        String modelIdPrefix = "http://model.geneontology.org/"; // this will not be used for anything
        CurieHandler curieHandler = new MappedCurieHandler();
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, journalFilePath, null, null, false);
        //in case of update rather than whole new journal
        Set<IRI> stored = new HashSet<IRI>(m3.getStoredModelIds());
        LOGGER.info("loading gocams from " + inputFolder);
        //for (File file : FileUtils.listFiles(new File(inputFolder), null, true)) {
        File i = new File(inputFolder);
        if (i.exists()) {
            if (i.isDirectory()) {
                total_files = i.listFiles().length;
                FileUtils.listFiles(i, null, true).parallelStream().parallel().forEach(file -> {
                    if (file.getName().endsWith("ttl")) {
                        java.util.Optional<String> irio;
                        try {
                            irio = m3.scanForOntologyIRI(file);
                            IRI iri = null;
                            if (irio.isPresent()) {
                                iri = IRI.create(irio.get());
                            }
                            //is it in there already?
                            if (stored.contains(iri)) {
                                LOGGER.error("Attempted to load gocam ttl file into database but gocam with that iri already exists, skipping " + file + " " + iri);
                            } else {
                                stored.add(iri);
                                m3.importModelToDatabase(file, true);
                            }
                        } catch (RDFParseException | RDFHandlerException | IOException e1) {
                            // TODO Auto-generated catch block
                            e1.printStackTrace();
                        } catch (OWLOntologyCreationException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (RepositoryException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    } else {
                        LOGGER.info("Ignored for not ending with .ttl" + file);
                    }
                });
            }
        }
        m3.dispose();
        LOGGER.info("done loading gocams, loaded: " + stored.size() + " out of: " + total_files + " files");
    }

    /**
     * @param journalFilePath
     * @param inputFolder
     * @throws Exception
     */
    public static void buildMergedOwlOntology(String inputFolder, String outputfile, String base_iri, boolean addInferences) throws Exception {
        // minimal inputs
        if (outputfile == null) {
            System.err.println("No output file was configured.");
            System.exit(-1);
            return;
        }
        if (inputFolder == null) {
            System.err.println("No input folder was configured.");
            System.exit(-1);
            return;
        }
        if (base_iri == null) {
            System.err.println("No base iri was configured.");
            System.exit(-1);
            return;
        }
        OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = ontman.getOWLDataFactory();
        OWLOntology merged = ontman.createOntology(IRI.create(base_iri));
        for (File file : FileUtils.listFiles(new File(inputFolder), null, true)) {
            LOGGER.info("Loading " + file);
            if (file.getName().endsWith("ttl") || file.getName().endsWith("owl")) {
                try {
                    OWLOntology ont = ontman.loadOntologyFromOntologyDocument(file);
                    ontman.addAxioms(merged, ont.getAxioms());
                } catch (OWLOntologyAlreadyExistsException e) {
                    LOGGER.error("error loading already loaded ontology: " + file);
                }
            } else {
                LOGGER.info("Ignored for not ending with .ttl or .owl " + file);
            }
        }
        if (addInferences) {
            LOGGER.info("Running reasoner");
            //OWLReasonerFactory reasonerFactory = new WhelkOWLReasonerFactory();
            //WhelkOWLReasoner reasoner = (WhelkOWLReasoner)reasonerFactory.createReasoner(merged);
            OWLReasonerFactory reasonerFactory = new StructuralReasonerFactory();
            OWLReasoner reasoner = reasonerFactory.createReasoner(merged);
            InferredOntologyGenerator gen = new InferredOntologyGenerator(reasoner);
            gen.fillOntology(df, merged);
        }
        try {
            ontman.saveOntology(merged, new FileOutputStream(new File(outputfile)));
        } catch (OWLOntologyStorageException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Load the go-cam files in the input folder into the journal
     * cli import-owl-models
     *
     * @param journalFilePath
     * @param inputFolder
     * @throws Exception
     */
    public static void importOWLOntologyIntoJournal(String journalFilePath, String inputFile, boolean reset) throws Exception {
        // minimal inputs
        if (journalFilePath == null) {
            System.err.println("No journal file was configured.");
            System.exit(-1);
            return;
        }
        if (inputFile == null) {
            System.err.println("No input file was configured.");
            System.exit(-1);
            return;
        }

        BlazegraphOntologyManager man = new BlazegraphOntologyManager(journalFilePath, false);
        String iri_for_ontology_graph = "http://geneontology.org/go-lego-graph";
        man.loadRepositoryFromOWLFile(new File(inputFile), iri_for_ontology_graph, reset);
    }

    /**
     * Updates the journal with the provided update sparql statement.
     * cli parameter --sparql-update
     *
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
     *
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
            LOGGER.info("Loading model from file: " + input);
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
        } finally {
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
                LOGGER.info("Saving json to file: " + outputFile);
            }
            IOUtils.write(json, outputStream);
        }
    }

    /**
     * Output GPAD files via inference+SPARQL
     * cli --lego-to-gpad-sparql
     *
     * @param modelIdPrefix
     * @param modelIdcurie
     * @param inputDB
     * @param gpadOutputFolder
     * @param ontologyIRI
     * @throws Exception
     */
    public static void legoToAnnotationsSPARQL(String modelIdPrefix, String modelIdcurie, String inputDB, String gpadOutputFolder, String ontologyIRI, String catalog, String go_lego_journal_file) throws Exception {
        if (modelIdPrefix == null) {
            modelIdPrefix = "http://model.geneontology.org/";
        }
        if (modelIdcurie == null) {
            modelIdcurie = "gomodel";
        }
        if (inputDB == null) {
            inputDB = "blazegraph.jnl";
        }
        if (gpadOutputFolder == null) {
            gpadOutputFolder = null;
        }
        if (ontologyIRI == null) {
            ontologyIRI = "http://purl.obolibrary.org/obo/go/extensions/go-lego.owl";
        }
        OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
        if (catalog != null) {
            LOGGER.info("using catalog: " + catalog);
            ontman.setIRIMappers(Sets.newHashSet(new owltools.io.CatalogXmlIRIMapper(catalog)));
        } else {
            LOGGER.info("no catalog, resolving all ontology uris directly");
        }

        OWLOntology ontology = ontman.loadOntology(IRI.create(ontologyIRI));
        CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
        CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
        boolean loadTboxIntoOntJournal = (!(new File(go_lego_journal_file)).exists());
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(ontology, curieHandler, modelIdPrefix, inputDB, null, go_lego_journal_file, false);
        if (loadTboxIntoOntJournal) {
            m3.getGolego_repo().loadRepositoryFromOntology(ontology, "http://example.org/", true);
        }
        final String immutableModelIdPrefix = modelIdPrefix;
        final String immutableGpadOutputFolder = gpadOutputFolder;
        m3.getAvailableModelIds().stream().parallel().forEach(modelIRI -> {
            try {
                //TODO investigate whether changing to a neo-lite model has an impact on this - may need to make use of ontology journal
                String gpad = new GPADSPARQLExport(curieHandler, m3.getLegacyRelationShorthandIndex(), m3.getTboxShorthandIndex(), m3.getGolego_repo().regulatorsToRegulated).exportGPAD(m3.createInferredModel(modelIRI), modelIRI);
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
     *
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
     * @throws IOException
     * @throws OWLOntologyCreationException
     */
    public static void validateGoCams(String input, String outputFolder,
                                      String ontologyIRI, String catalog, String modelIdPrefix, String modelIdcurie,
                                      String shexpath, String shapemappath, boolean travisMode, boolean shouldFail, boolean checkShex,
                                      String go_lego_journal_file, boolean run_reasoner_report, boolean checkGraphType) throws OWLOntologyCreationException, IOException {
        LOGGER.setLevel(Level.INFO);
        String inputDB = "blazegraph.jnl";
        String shexFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shex";
        String goshapemapFileUrl = "https://raw.githubusercontent.com/geneontology/go-shapes/master/shapes/go-cam-shapes.shapeMap";
        CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
        CurieHandler curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
        Map<String, String> modelid_filename = new HashMap<String, String>();

        if (outputFolder == null) {
            LOGGER.error("please specify an output folder with -r ");
            System.exit(-1);
        } else if (!outputFolder.endsWith("/")) {
            outputFolder += "/";
        }

        if (input == null) {
            LOGGER.error("please provide an input file - either a directory of ttl files or a blazegraph journal");
            System.exit(-1);
        }

        LOGGER.info("loading tbox ontology: " + ontologyIRI);
        OWLOntologyManager ontman = OWLManager.createOWLOntologyManager();
        if (catalog != null) {
            LOGGER.info("using catalog: " + catalog);
            try {
                ontman.setIRIMappers(Sets.newHashSet(new CatalogXmlIRIMapper(catalog)));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            LOGGER.info("no catalog, resolving all ontology uris directly");
        }

        OWLOntology tbox_ontology = null;
        try {
            tbox_ontology = ontman.loadOntology(IRI.create(ontologyIRI));
            LOGGER.info("tbox ontology axioms loaded: " + tbox_ontology.getAxiomCount());
        } catch (OWLOntologyCreationException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        //either load directly from existing journal
        if (input.endsWith(".jnl")) {
            inputDB = input;
        } else {
            //or make sure that the journal file provided is cleared out and ready
            File i = new File(input);
            if (i.exists()) {
                //remove anything that existed earlier
                File bgdb = new File(inputDB);
                if (bgdb.exists()) {
                    bgdb.delete();
                }
            }
        }
        //make the manager
        LOGGER.info("Setting up model manager and initializing rules for Arachne reasoner");
        UndoAwareMolecularModelManager m3 = new UndoAwareMolecularModelManager(tbox_ontology, curieHandler, modelIdPrefix, inputDB, null, go_lego_journal_file, true);
        //if provided a directory as input, load them ttl files into the manager
        File i = new File(input);
        if (i.exists() && !input.endsWith(".jnl")) {
            if (i.isDirectory()) {
                LOGGER.info("Loading models from " + i.getAbsolutePath());
                Set<String> model_iris = new HashSet<String>();
                FileUtils.listFiles(i, null, true).parallelStream().parallel().forEach(file -> {
                    if (file.getName().endsWith(".ttl") || file.getName().endsWith("owl")) {
                        try {
                            String modeluri = m3.importModelToDatabase(file, true);
                            if (modeluri == null) {
                                LOGGER.error("Null model IRI: " + modeluri + " file: " + file);
                            } else if (!model_iris.add(modeluri)) {
                                LOGGER.error("Multiple models with same IRI: " + modeluri + " file: " + file + " file: " + modelid_filename.get(modeluri));
                            } else {
                                modelid_filename.put(modeluri, file.getName());
                            }
                        } catch (OWLOntologyCreationException | RepositoryException | RDFParseException
                                 | RDFHandlerException | IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                });
            } else {//just load the one provided
                LOGGER.info("Loading " + i);
                try {
                    m3.importModelToDatabase(i, true);
                } catch (OWLOntologyCreationException | RepositoryException | RDFParseException
                         | RDFHandlerException | IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            LOGGER.info("loaded files into blazegraph journal: " + input);
        }
        //models ready
        //now set up shex validator
        if (shexpath == null) {
            //fall back on downloading from shapes repo
            URL shex_schema_url;
            try {
                shex_schema_url = new URL(shexFileUrl);
                shexpath = "./go-cam-schema.shex";
                File shex_schema_file = new File(shexpath);
                org.apache.commons.io.FileUtils.copyURLToFile(shex_schema_url, shex_schema_file);
                System.err.println("-s .No shex schema provided, using: " + shexFileUrl);
            } catch (MalformedURLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        if (shapemappath == null) {
            URL shex_map_url;
            try {
                shex_map_url = new URL(goshapemapFileUrl);
                shapemappath = "./go-cam-shapes.shapeMap";
                File shex_map_file = new File(shapemappath);
                org.apache.commons.io.FileUtils.copyURLToFile(shex_map_url, shex_map_file);
                System.err.println("-m .No shape map file provided, using: " + goshapemapFileUrl);
            } catch (MalformedURLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        LOGGER.info("making shex validator: " + shexpath + " " + shapemappath + " " + curieHandler + " ");
        MinervaShexValidator shex = null;
        try {
            shex = new MinervaShexValidator(shexpath, shapemappath, curieHandler, m3.getGolego_repo());
        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        if (checkShex) {
            shex.setActive(true);
        } else {
            shex.setActive(false);
        }

        //shex validator is ready, now build the inference provider (which provides access to the shex validator and provides inferences useful for shex)
        String reasonerOpt = "arachne";
        LOGGER.info("Building OWL inference provider: " + reasonerOpt);
        InferenceProviderCreator ipc = StartUpTool.createInferenceProviderCreator(reasonerOpt, m3, shex);
        LOGGER.info("Validating models: " + reasonerOpt);

        //Set up all the report files.
        String basic_output_file = outputFolder + "main_report.txt";
        String explanations_file = outputFolder + "explanations.txt";
        String activity_output_file = outputFolder + "activity_report.txt";
        if (outputFolder != null) {
            try {
                //valid or not
                FileWriter basic_shex_output = new FileWriter(basic_output_file, false);
                basic_shex_output.write("filename\tmodel_title\tmodel_url\tmodelstate\tcontributor\tprovider\tdate\tOWL_consistent\tshex_valid\tshex_meta_problem\tshex_data_problem\tvalidation_time_milliseconds\taxioms\tn_rows_gpad\t");
                basic_shex_output.write(GoCamModelStats.statsHeader() + "\n");
                basic_shex_output.close();
                //tab delimited explanations for failures
                FileWriter explanations = new FileWriter(explanations_file, false);
                explanations.write("filename\tmodel_title\tmodel_iri\tnode\tNode_types\tproperty\tIntended_range_shapes\tobject\tObject_types\tObject_shapes\n");
                explanations.close();
                //tab delimited summary of properties of activity units
                FileWriter activity_output = new FileWriter(activity_output_file, false);
                activity_output.write("filename\tmodel_title\tmodel_url\tmodelstate\tcontributor\tprovider\tdate\tactivity_iri\tactivity_xref\tactivity_label\tcomplete\tinputs\toutputs\tenablers\tlocations\tcausal upstream\tcausal downstream\tpart of n BP\tMF\tBP\n");
                activity_output.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        //this will generate the json file used for the go rules report for the pipeline
        BatchPipelineValidationReport pipe_report = null;
        Set<ErrorMessage> owl_errors = new HashSet<ErrorMessage>();
        Set<ErrorMessage> shex_errors = new HashSet<ErrorMessage>();
        pipe_report = new BatchPipelineValidationReport();
        try {
            pipe_report.setNumber_of_models(m3.getAvailableModelIds().size());
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        int bad_models = 0;
        int good_models = 0;
        final boolean shex_output = checkShex;

        //only used if OWL reasoning report is requested
        ReasonerReport reasoner_report = null;
        if (run_reasoner_report) {
            reasoner_report = initReasonerReport(outputFolder);
        }
        //now process each gocam
        try {
            for (IRI modelIRI : m3.getAvailableModelIds()) {
                long start = System.currentTimeMillis();
                String filename = modelid_filename.get(modelIRI.toString());
                boolean isConsistent = true; //OWL
                boolean isConformant = true; //shex
                if (filename != null) {
                    LOGGER.info("processing " + filename + "\t" + modelIRI);
                } else {
                    LOGGER.info("processing \t" + modelIRI);
                }
                //this is where everything actually happens
                ModelContainer mc = m3.getModel(modelIRI);
                OWLOntology gocam = mc.getAboxOntology();
                final IRI graphType = IRI.create("http://model.geneontology.org/graphType");
                final IRI noctuaCam = IRI.create("http://model.geneontology.org/noctuaCam");
                if (checkGraphType) {
                    if (gocam.getAnnotations().stream().noneMatch(annotation ->
                            annotation.getProperty().getIRI().equals(graphType)
                                    && annotation.getValue().isIRI()
                                    && annotation.getValue().equals(noctuaCam))) {
                        System.out.println("SKIPPING: " + modelIRI);
                        continue;
                    }
                }
                try {
                    //if a model does not have an import statement that links in an ontology that defines all of its classes and object properties
                    //or if the model does not define the classes and object properties itself, parsing problems will prevail
                    //this step makes sure that does not happen
                    gocam = CoreMolecularModelManager.fixBrokenObjectPropertiesAndAxioms(gocam);
                } catch (OWLOntologyCreationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                LOGGER.info("preparing model stats...");
                //The GoCamModel code is used to capture model-level statistics such as 'how many causal relations are there?'
                //This might be an area for a speed improvement if needed
                GoCamModel gcm = new GoCamModel(gocam, m3);
                String title = "title";
                if (gcm.getTitle() != null) {
                    title = makeColSafe(gcm.getTitle());
                } else {
                    LOGGER.error("no title for " + filename);
                }
                //this is to make clickable links in reports
                String link = modelIRI.toString().replace("http://model.geneontology.org/", "http://noctua.geneontology.org/editor/graph/gomodel:");
                if (modelIRI.toString().contains("R-HSA")) {
                    link = link.replace("noctua.geneontology", "noctua-dev.berkeleybop");
                }
                String modelstate = makeColSafe(gcm.getModelstate());
                String contributor = makeColSafe(gcm.getContributors().toString());
                String date = makeColSafe(gcm.getDate());
                String provider = makeColSafe(gcm.getProvided_by().toString());
                pipe_report.setTaxa(gcm.getIn_taxon());
                LOGGER.info("model stats done for title: " + title);
                int axioms = gocam.getAxiomCount();
                //add activity level statistics as a default
                FileWriter activity_output = new FileWriter(activity_output_file, true);
                for (ActivityUnit unit : gcm.getActivities()) {
                    activity_output.write(filename + "\t" + title + "\t" + link + "\t" + modelstate + "\t" + contributor + "\t" + provider + "\t" + date + "\t" + unit.getIndividual().getIRI().toString() + "\t" + unit.getXref() + "\t" + unit.getLabel() + "\t");
                    activity_output.write(unit.isComplete() + "\t" + unit.getInputs().size() + "\t" + unit.getOutputs().size() + "\t" + unit.getEnablers().size() + "\t" + unit.getLocations().size() +
                            "\t" + unit.getCausal_in().size() + "\t" + unit.getCausal_out().size() + "\t" + unit.getContaining_processes().size() + "\t" + unit.stringForClasses(unit.getDirect_types()) + "\t" + unit.getURIsForConnectedBPs() + "\n");
                }
                activity_output.close();

                InferenceProvider ip = ipc.create(mc);
                isConsistent = ip.isConsistent();
                //TODO re-use reasoner object from ip
                //TODO this is another area that could be touched/removed for speed improvement
                int n_rows_gpad = 0;
                if (isConsistent) {
                    try {
                        Set<GPADData> gpad = new GPADSPARQLExport(curieHandler, m3.getLegacyRelationShorthandIndex(), m3.getTboxShorthandIndex(), m3.getGolego_repo().regulatorsToRegulated).getGPAD(m3.createInferredModel(modelIRI), modelIRI);
                        if (gpad != null) {
                            n_rows_gpad = gpad.size();
                        }
                    } catch (InconsistentOntologyException e) {
                        LOGGER.error("inconsistent ontology, can't make gpad");
                    }
                }
                long done = System.currentTimeMillis();
                long milliseconds = (done - start);
                //for rules report in pipeline
                if (!ip.isConsistent()) {
                    String level = "ERROR";
                    String model_id = curieHandler.getCuri(modelIRI);
                    String message = BatchPipelineValidationReport.getOwlMessage();
                    int rule = BatchPipelineValidationReport.getOwlRule();
                    ErrorMessage owl = new ErrorMessage(level, model_id, gcm.getIn_taxon(), message, rule);
                    owl_errors.add(owl);
                }
                if (!isConsistent) {
                    FileWriter explanations = new FileWriter(explanations_file, true);
                    explanations.write(filename + "\t" + title + "\t" + modelIRI + "\tOWL fail explanation: " + ip.getValidation_results().getOwlvalidation().getAsText() + "\n");
                    explanations.close();
                }
                //travis mode causes the system to exit when an invalid model is detected (unless shouldFail is on)
                if (travisMode && !isConsistent) {
                    if (!shouldFail) {
                        LOGGER.error(filename + "\t" + title + "\t" + modelIRI + "\tOWL:is inconsistent, quitting");
                        System.exit(-1);
                    }
                }
                //basic is just one row per model - did it validate or not
                FileWriter basic = new FileWriter(basic_output_file, true);
                if (!shex_output) {
                    if (ip.isConsistent()) {
                        good_models++;
                    } else {
                        bad_models++;
                    }
                } else {
                    ValidationResultSet validations = ip.getValidation_results();
                    isConformant = validations.allConformant();
                    if (isConformant) {
                        good_models++;
                    } else {
                        bad_models++;
                    }
                    if (!validations.getShexvalidation().isConformant()) {
                        String level = "WARNING";
                        String model_id = curieHandler.getCuri(modelIRI);
                        String message = BatchPipelineValidationReport.getShexMessage();
                        int rule = BatchPipelineValidationReport.getShexRule();
                        ErrorMessage shex_message = new ErrorMessage(level, model_id, gcm.getIn_taxon(), message, rule);
                        boolean include_explanations_in_json = true; //TODO set as a parameter
                        if (include_explanations_in_json) {
                            shex_message.setExplanations(validations);
                        }
                        shex_errors.add(shex_message);
                        FileWriter explanations = new FileWriter(explanations_file, true);
                        explanations.write(ip.getValidation_results().getShexvalidation().getAsTab(filename + "\t" + title + "\t" + modelIRI));
                        explanations.close();
                    }
                    if (travisMode) {
                        if (!isConformant && !shouldFail) {
                            LOGGER.error(filename + "\t" + title + "\t" + modelIRI + "\tshex is nonconformant, quitting, explanation:\n" + ip.getValidation_results().getShexvalidation().getAsText());
                            System.exit(-1);
                        } else if (isConformant && shouldFail) {
                            LOGGER.error(filename + "\t" + title + "\t" + modelIRI + "\tshex validates, but it should not be, quitting");
                            System.exit(-1);
                        }
                    }
                    //is it a metadata violation or data ?
                    boolean shex_meta_problem = false;
                    boolean shex_data_problem = false;
                    if (!validations.getShexvalidation().isConformant()) {
                        String model_curie = curieHandler.getCuri(modelIRI);
                        ValidationResultSet validationset = ip.getValidation_results();
                        ShexValidationReport shex_report = validationset.getShexvalidation();
                        Set<Violation> violations = shex_report.getViolations();
                        if (violations != null) {
                            for (Violation v : violations) {
                                if (v.getNode().equals(model_curie)) {
                                    shex_meta_problem = true;
                                } else {
                                    shex_data_problem = true;
                                }
                            }
                        } else {
                            LOGGER.error("Invalid model but no violations reported");
                        }
                    }
                    LOGGER.info(filename + "\t" + title + "\t" + modelIRI + "\tOWL:" + isConsistent + "\tshex:" + isConformant);
                    basic.write(filename + "\t" + title + "\t" + link + "\t" + modelstate + "\t" + contributor + "\t" + provider + "\t" + date + "\t" + isConsistent + "\t" + isConformant + "\t" + shex_meta_problem + "\t" + shex_data_problem + "\t" + milliseconds + "\t" + axioms + "\t" +
                            n_rows_gpad + "\t" + gcm.getGoCamModelStats().stats2cols() + "\n");
                }
                basic.close();
                if (run_reasoner_report) {
                    addReasonerReport(outputFolder, gocam, ip, title, reasoner_report);
                }
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (run_reasoner_report) {
            summarizeReasonerReport(outputFolder, reasoner_report);
        }

        pipe_report.setNumber_of_correct_models(good_models);
        pipe_report.setNumber_of_models_in_error(bad_models);
        pipe_report.getMessages().put(BatchPipelineValidationReport.getShexRuleString(), shex_errors);
        pipe_report.getMessages().put(BatchPipelineValidationReport.getOwlRuleString(), owl_errors);
        GsonBuilder builder = new GsonBuilder();
        Gson gson = builder.setPrettyPrinting().create();
        String json = gson.toJson(pipe_report);
        try {
            FileWriter pipe_json = new FileWriter(outputFolder + "gorules_report.json", false);
            pipe_json.write(json);
            pipe_json.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        m3.dispose();
        LOGGER.info("done with validation");
    }

    static class ReasonerReport {
        Map<String, Integer> term_asserted_instances_mapped = new HashMap<String, Integer>();
        Map<String, Integer> term_deepened_instances_mapped = new HashMap<String, Integer>();
        Map<String, Integer> term_asserted_instances_created = new HashMap<String, Integer>();
        Map<String, Integer> term_deepened_instances_created = new HashMap<String, Integer>();
    }


    private static ReasonerReport initReasonerReport(String outputFolder) {
        String reasoner_report_file = outputFolder + "reasoner_report_all.txt";
        FileWriter reasoner_report;
        try {
            reasoner_report = new FileWriter(reasoner_report_file, false);
            reasoner_report.write("title\tindividual\txref\tasserted\tinferred\n");
            reasoner_report.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        ReasonerReport report = new ReasonerReport();
        return report;
    }

    private static ReasonerReport addReasonerReport(String outputFolder, OWLOntology gocam, InferenceProvider ip, String title, ReasonerReport report) throws IOException {
        String reasoner_report_file = outputFolder + "reasoner_report_all.txt";
        FileWriter reasoner_report = new FileWriter(reasoner_report_file, true);
        Set<OWLNamedIndividual> individuals = gocam.getIndividualsInSignature();
        for (OWLNamedIndividual individual : individuals) {
            //what kind of individual - mapped or created.  mapped have xrefs, created do not.
            String xref = "none";
            for (OWLAnnotation anno : EntitySearcher.getAnnotations(individual, gocam)) {
                if (anno.getProperty().getIRI().toString().equals("http://www.geneontology.org/formats/oboInOwl#hasDbXref")) {
                    xref = anno.getValue().asLiteral().get().getLiteral();
                }
            }

            Collection<OWLClassExpression> asserted_ce = EntitySearcher.getTypes(individual, gocam);
            Set<OWLClass> asserted = new HashSet<OWLClass>();
            for (OWLClassExpression ce : asserted_ce) {
                if (!ce.isAnonymous()) {
                    OWLClass a = ce.asOWLClass();
                    if (a.isBuiltIn() == false) {
                        asserted.add(a);
                    }
                }
            }
            Set<OWLClass> inferred_direct = new HashSet<>();
            Set<OWLClass> flattened = ip.getTypes(individual);
            for (OWLClass cls : flattened) {
                if (cls.isBuiltIn() == false) {
                    inferred_direct.add(cls);
                }
            }
            inferred_direct.removeAll(asserted);
            reasoner_report.write(title + "\t" + individual.getIRI() + "\t" + xref + "\t" + asserted + "\t" + inferred_direct + "\n");
            if (asserted != null) {
                for (OWLClass go : asserted) {
                    if (xref.equals("none")) {
                        Integer n = report.term_asserted_instances_created.get(go.toString());
                        if (n == null) {
                            n = 0;
                        }
                        n = n + 1;
                        report.term_asserted_instances_created.put(go.toString(), n);

                        if (inferred_direct != null && inferred_direct.size() > 0) {
                            Integer deepened = report.term_deepened_instances_created.get(go.toString());
                            if (deepened == null) {
                                deepened = 0;
                            }
                            deepened = deepened + 1;
                            report.term_deepened_instances_created.put(go.toString(), deepened);
                        }
                    } else {
                        Integer n = report.term_asserted_instances_mapped.get(go.toString());
                        if (n == null) {
                            n = 0;
                        }
                        n = n + 1;
                        report.term_asserted_instances_mapped.put(go.toString(), n);

                        if (inferred_direct != null && inferred_direct.size() > 0) {
                            Integer deepened = report.term_deepened_instances_mapped.get(go.toString());
                            if (deepened == null) {
                                deepened = 0;
                            }
                            deepened = deepened + 1;
                            report.term_deepened_instances_mapped.put(go.toString(), deepened);
                        }
                    }
                }
            }
        }
        reasoner_report.close();
        return report;
    }

    private static void summarizeReasonerReport(String outputFolder, ReasonerReport report) {
        String reasoner_report_summary_file = outputFolder + "reasoner_report_summary.txt";
        FileWriter reasoner_report_summary;
        try {
            reasoner_report_summary = new FileWriter(reasoner_report_summary_file, false);
            reasoner_report_summary.write("asserted GO term\tmapped individual count\tmapped N deepened\tcreated individual count\tcreated N deepened\n");
            Set<String> terms = new HashSet<String>();
            terms.addAll(report.term_asserted_instances_mapped.keySet());
            terms.addAll(report.term_asserted_instances_created.keySet());
            for (String goterm : terms) {
                int n_deepened_mapped = 0;
                int n_mapped = 0;
                if (report.term_asserted_instances_mapped.containsKey(goterm)) {
                    n_mapped = report.term_asserted_instances_mapped.get(goterm);
                }

                if (report.term_deepened_instances_mapped.get(goterm) != null) {
                    n_deepened_mapped = report.term_deepened_instances_mapped.get(goterm);
                }
                int n_deepened_created = 0;
                int n_created = 0;
                if (report.term_asserted_instances_created.containsKey(goterm)) {
                    n_created = report.term_asserted_instances_created.get(goterm);
                }
                if (report.term_deepened_instances_created.get(goterm) != null) {
                    n_deepened_created = report.term_deepened_instances_created.get(goterm);
                }
                reasoner_report_summary.write(goterm + "\t" + n_mapped + "\t" + n_deepened_mapped + "\t" + n_created + "\t" + n_deepened_created + "\n");
            }
            reasoner_report_summary.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }


    private static String makeColSafe(String text) {
        text = text.replaceAll("\n", " ");
        text = text.replaceAll("\r", " ");
        text = text.replaceAll("\t", " ");
        return text;
    }

    public static void addTaxonMetaData(String go_cam_journal, String go_lego_journal_file) throws OWLOntologyCreationException, IOException {
        String modelIdPrefix = "http://model.geneontology.org/";
        OWLOntology dummy = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/dummy"));
        CurieHandler curieHandler = new MappedCurieHandler();
        BlazegraphMolecularModelManager<Void> m3 = new BlazegraphMolecularModelManager<>(dummy, curieHandler, modelIdPrefix, go_cam_journal, null, go_lego_journal_file, true);
        m3.addTaxonMetadata();
    }

    public static void cleanGoCams(String input_dir, String output_dir) {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        File directory = new File(input_dir);
        boolean ignore_imports = true;
        if (directory.isDirectory()) {
            for (File file : directory.listFiles()) {
                if (file.getName().endsWith("ttl")) {
                    System.out.println("fixing " + file.getAbsolutePath());
                    final IRI modelFile = IRI.create(file.getAbsoluteFile());
                    OWLOntology o;
                    try {
                        o = CoreMolecularModelManager.loadOntologyDocumentSource(new IRIDocumentSource(modelFile), ignore_imports, m);
                        //in case the reader was confused by the missing import, fix declarations
                        o = CoreMolecularModelManager.fixBrokenObjectPropertiesAndAxioms(o);
                        //clean the model
                        OWLOntology cleaned_ont = CoreMolecularModelManager.removeDeadAnnotationsAndImports(o);
                        //saved the blessed ontology
                        OWLDocumentFormat owlFormat = new TurtleDocumentFormat();
                        m.setOntologyFormat(cleaned_ont, owlFormat);
                        String cleaned_ont_file = output_dir + file.getName();
                        try {
                            m.saveOntology(cleaned_ont, new FileOutputStream(cleaned_ont_file));
                        } catch (OWLOntologyStorageException | FileNotFoundException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    } catch (OWLOntologyCreationException e1) {
                        // TODO Auto-generated catch block
                        e1.printStackTrace();
                    }
                }
            }
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
        System.out.println(key + "\t" + value);
        return value;
    }

    public static void reportSystemParams() {
        /* Total number of processors or cores available to the JVM */
        LOGGER.info("Available processors (cores): " +
                Runtime.getRuntime().availableProcessors());

        /* Total amount of free memory available to the JVM */
        LOGGER.info("Free memory (m bytes): " +
                Runtime.getRuntime().freeMemory() / 1048576);

        /* This will return Long.MAX_VALUE if there is no preset limit */
        long maxMemory = Runtime.getRuntime().maxMemory() / 1048576;
        /* Maximum amount of memory the JVM will attempt to use */
        LOGGER.info("Maximum memory (m bytes): " +
                (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory));

        /* Total memory currently in use by the JVM */
        LOGGER.info("Total memory (m bytes): " +
                Runtime.getRuntime().totalMemory() / 1048576);

        /* Get a list of all filesystem roots on this system */
        File[] roots = File.listRoots();

        /* For each filesystem root, print some info */
        for (File root : roots) {
            LOGGER.info("File system root: " + root.getAbsolutePath());
            LOGGER.info("Total space (bytes): " + root.getTotalSpace());
            LOGGER.info("Free space (bytes): " + root.getFreeSpace());
            LOGGER.info("Usable space (bytes): " + root.getUsableSpace());
        }
    }

}
