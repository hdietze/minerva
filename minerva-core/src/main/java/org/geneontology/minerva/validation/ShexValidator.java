/**
 *
 */
package org.geneontology.minerva.validation;

import fr.inria.lille.shexjava.schema.Label;
import fr.inria.lille.shexjava.schema.ShexSchema;
import fr.inria.lille.shexjava.schema.abstrsynt.*;
import fr.inria.lille.shexjava.schema.parsing.GenParser;
import fr.inria.lille.shexjava.util.Interval;
import fr.inria.lille.shexjava.util.Pair;
import fr.inria.lille.shexjava.validation.RecursiveValidationWithMemorization;
import fr.inria.lille.shexjava.validation.RefineValidation;
import fr.inria.lille.shexjava.validation.Status;
import fr.inria.lille.shexjava.validation.Typing;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.rdf.api.*;
import org.apache.commons.rdf.simple.SimpleRDF;
import org.apache.jena.commonsrdf.JenaCommonsRDF;
import org.apache.jena.commonsrdf.JenaRDF;
import org.apache.jena.commonsrdf.impl.JenaGraph;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.sys.JenaSystem;
import org.apache.log4j.Logger;
import org.geneontology.minerva.BlazegraphOntologyManager;
import org.geneontology.minerva.curie.CurieHandler;
import org.semanticweb.owlapi.model.IRI;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * @author bgood
 *
 */
public class ShexValidator {
    private static final Logger LOGGER = Logger.getLogger(ShexValidator.class);
    public ShexSchema schema;
    public Map<String, String> GoQueryMap;
    //	public OWLReasoner tbox_reasoner;
    private BlazegraphOntologyManager go_lego_repo;
    public static final String endpoint = "http://rdf.geneontology.org/blazegraph/sparql";
    public Map<Label, Map<String, Set<String>>> shape_expected_property_ranges;
    public Map<Label, Map<String, Interval>> shape_expected_property_cardinality;
    Map<Label, Interval> tripexprlabel_cardinality;
    public CurieHandler curieHandler;
    public RDF rdfFactory;
    public final int timeout_mill = 30000;

    /**
     * @throws Exception
     *
     */
    public ShexValidator(String shexpath, String goshapemappath, BlazegraphOntologyManager go_lego, CurieHandler curieHandler_) throws Exception {
        init(new File(shexpath), new File(goshapemappath), go_lego, curieHandler_);
    }

    public ShexValidator(File shex_schema_file, File shex_map_file, BlazegraphOntologyManager go_lego, CurieHandler curieHandler_) throws Exception {
        init(shex_schema_file, shex_map_file, go_lego, curieHandler_);
    }

    public void init(File shex_schema_file, File shex_map_file, BlazegraphOntologyManager go_lego, CurieHandler curieHandler_) throws Exception {
        JenaSystem.init();
        schema = GenParser.parseSchema(shex_schema_file.toPath());
        GoQueryMap = makeGoQueryMap(shex_map_file.getAbsolutePath());
        //tbox_reasoner = tbox_reasoner_;
        setGo_lego_repo(go_lego);
        shape_expected_property_ranges = new HashMap<Label, Map<String, Set<String>>>();
        shape_expected_property_cardinality = new HashMap<Label, Map<String, Interval>>();
        tripexprlabel_cardinality = new HashMap<Label, Interval>();
        curieHandler = curieHandler_;
        rdfFactory = new SimpleRDF();
        for (String shapelabel : GoQueryMap.keySet()) {
            if (shapelabel.equals("http://purl.obolibrary.org/obo/go/shapes/AnnotatedEdge")) {
                continue;
            }
            Label shape_label = new Label(rdfFactory.createIRI(shapelabel));
            ShapeExpr rule = schema.getRules().get(shape_label);
            Map<String, Set<String>> expected_property_ranges = getPropertyRangeMap(shape_label, rule, null);
            shape_expected_property_ranges.put(shape_label, expected_property_ranges);
        }
        LOGGER.info("shex validator ready");
    }

    public static Map<String, String> makeGoQueryMap(String shapemap_file) throws IOException {
        Map<String, String> shapelabel_sparql = new HashMap<String, String>();
        BufferedReader reader = new BufferedReader(new FileReader(shapemap_file));
        String line = reader.readLine();
        String all = line;
        while (line != null) {
            all += line;
            line = reader.readLine();
        }
        reader.close();
        String[] maps = all.split(",");
        for (String map : maps) {
            String sparql = StringUtils.substringBetween(map, "'", "'");
            sparql = sparql.replace("a/", "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?c . ?c ");
            String[] shapemaprow = map.split("@");
            String shapelabel = shapemaprow[1];
            shapelabel = shapelabel.replace(">", "");
            shapelabel = shapelabel.replace("<", "");
            shapelabel = shapelabel.trim();
            shapelabel_sparql.put(shapelabel, sparql);
        }
        return shapelabel_sparql;
    }

    public ShexValidationReport runShapeMapValidation(Model test_model) {
        boolean explain = true;
        ShexValidationReport r = new ShexValidationReport();
        //this shex implementation likes to use the commons JenaRDF interface, nothing exciting here
        Graph shexy_graph = JenaCommonsRDF.fromJena(test_model.getGraph());
        boolean all_good = true;
        try {
            Typing all_typed = runRefineWithTimeout(shexy_graph);
            if (all_typed != null) {
                //filter to most specific tests
                Map<Resource, Set<String>> node_s_shapes = getShapesToTestForEachResource(test_model);
                for (Resource node : node_s_shapes.keySet()) {
                    Set<String> shapes = node_s_shapes.get(node);
                    for (String shapelabel : shapes) {
                        Label shape_label = new Label(rdfFactory.createIRI(shapelabel));
                        RDFTerm focus_node = null;
                        if (node.isURIResource()) {
                            focus_node = rdfFactory.createIRI(node.getURI());
                        } else {
                            focus_node = rdfFactory.createBlankNode(node.getId().getLabelString());
                        }
                        if (!all_typed.isConformant(focus_node, shape_label)) {
                            //something didn't match expectations
                            all_good = false;
                            //try to explain the mismatch
                            if (explain) {
                                Violation violation;
                                try {
                                    violation = getViolationForMismatch(shape_label, node, all_typed, test_model);
                                    r.addViolation(violation);
                                } catch (IOException e) {
                                    // TODO Auto-generated catch block
                                    e.printStackTrace();
                                }
                            }
                        }
                        //run our local CLOSE check
                        //TODO remove if we implement closed directly
                        if (explain) {
                            Set<ShexViolation> extra_violations;
                            try {
                                extra_violations = checkForExtraProperties(node, test_model, shape_label, all_typed);
                                if (extra_violations != null && !extra_violations.isEmpty()) {
                                    r.addViolations(extra_violations);
                                    all_good = false;
                                }
                            } catch (IOException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        }
                    }
                }
            } else {
                //validation failed
                all_good = false;
                r.setError_message("validation (with Refine algorithm) failed or timed out for this model");
            }
        } finally {
            try {
                //make sure to free up resources here.
                shexy_graph.close();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        r.conformant = all_good;
        return r;
    }

    private Violation getViolationForMismatch(Label shape_label, Resource focus_node, Typing typing, Model test_model) throws IOException {

        RDFTerm rdfterm = null;
        if (focus_node.isURIResource()) {
            rdfterm = rdfFactory.createIRI(focus_node.getURI());
        } else {
            rdfterm = rdfFactory.createIRI(focus_node.toString());
        }
        Status status = typing.getStatus(rdfterm, shape_label);
        if (status.equals(Status.NONCONFORMANT)) {
            //implementing a start on a generic violation report structure here
            ShexViolation violation = new ShexViolation(getCurie(focus_node.toString()));
            ShexExplanation explanation = new ShexExplanation();
            String shape_curie = getCurie(shape_label.stringValue());
            explanation.setShape(shape_curie);
            Set<ShexConstraint> unmet_constraints = getUnmetConstraints(focus_node, shape_label, test_model, typing);
            if (unmet_constraints != null) {
                for (ShexConstraint constraint : unmet_constraints) {
                    explanation.addConstraint(constraint);
                    violation.addExplanation(explanation);
                }
            } else {
                explanation.setErrorMessage("explanation computation timed out");
                violation.addExplanation(explanation);
            }
            return violation;
        } else if (status.equals(Status.NOTCOMPUTED)) {
            //if any of these are not computed, there is a problem
            String error = focus_node + " was not tested against " + shape_label;
            LOGGER.error(error);
        } else if (status.equals(Status.CONFORMANT)) {
            LOGGER.error("node is valid, should not be here trying to make a violation");
        }

        //	else {
        LOGGER.error("tried to explain shape violation on anonymous node: " + shape_label + " " + focus_node);
        StmtIterator node_statements = test_model.listStatements(focus_node.asResource(), null, (RDFNode) null);
        if (node_statements.hasNext()) {
            while (node_statements.hasNext()) {
                Statement s = node_statements.next();
            }
        }
        StmtIterator literal_statements = test_model.listStatements(focus_node.asResource(), null, (Literal) null);
        if (literal_statements.hasNext()) {
            while (literal_statements.hasNext()) {
                Statement s = literal_statements.next();
            }
        }
        //		}
        return null;
    }

    public Violation getTimeoutViolation(String node, String shapelabel) {
        ShexViolation violation = new ShexViolation(node);
        ShexExplanation explanation = new ShexExplanation();
        String shape_curie = getCurie(shapelabel);
        explanation.setShape(shape_curie);
        explanation.setErrorMessage("validation timed out");
        violation.addExplanation(explanation);
        return violation;
    }


    public ShexValidationReport runShapeMapValidationWithRecursiveSingleNodeValidation(Model test_model, boolean stream_output) throws Exception {
        ShexValidationReport r = new ShexValidationReport();
        JenaRDF jr = new JenaRDF();
        //this shex implementation likes to use the commons JenaRDF interface, nothing exciting here
        Graph shexy_graph = JenaCommonsRDF.fromJena(test_model.getGraph());
        //recursive only checks the focus node against the chosen shape.
        RecursiveValidationWithMemorization shex_model_validator = new RecursiveValidationWithMemorization(schema, shexy_graph);
        //for each shape in the query map (e.g. MF, BP, CC, etc.)

        boolean all_good = true;
        Map<Resource, Set<String>> node_s_shapes = getShapesToTestForEachResource(test_model);

        for (Resource focus_node_resource : node_s_shapes.keySet()) {
            Set<String> shape_nodes = node_s_shapes.get(focus_node_resource);

            for (String shapelabel : shape_nodes) {
                Label shape_label = new Label(rdfFactory.createIRI(shapelabel));
                if (focus_node_resource == null) {
                    System.out.println("null focus node for shape " + shape_label);
                    continue;
                }
                //check for use of properties not defined for this shape (okay if OPEN, not if CLOSED)
                Typing typing = validateNodeWithTimeout(shex_model_validator, focus_node_resource, shape_label);

                if (typing != null) {
                    Set<ShexViolation> extra_prop_violations = checkForExtraProperties(focus_node_resource, test_model, shape_label, typing);
                    if (extra_prop_violations != null && !extra_prop_violations.isEmpty()) {
                        for (Violation v : extra_prop_violations) {
                            r.addViolation(v);
                        }
                        all_good = false;
                    }
                    //run the validation on the node if possible..
                    RDFTerm focus_node = null;
                    String focus_node_id = "";
                    if (focus_node_resource.isURIResource()) {
                        focus_node = rdfFactory.createIRI(focus_node_resource.getURI());
                        focus_node_id = focus_node_resource.getURI();
                    } else {
                        focus_node = rdfFactory.createBlankNode(focus_node_resource.getId().getLabelString());
                        focus_node_id = focus_node_resource.getId().getLabelString();
                    }
                    //deal with curies for output
                    String node = focus_node_id;
                    node = getCurie(focus_node_id);
                    Status status = typing.getStatus(focus_node, shape_label);
                    if (status.equals(Status.CONFORMANT)) {
                        Set<String> shape_ids = r.node_matched_shapes.get(node);
                        if (shape_ids == null) {
                            shape_ids = new HashSet<String>();
                        }
                        shape_ids.add(shapelabel);
                        r.node_matched_shapes.put(node, shape_ids);
                    } else if (status.equals(Status.NONCONFORMANT)) {
                        all_good = false;
                        //implementing a start on a generic violation report structure here
                        ShexViolation violation = new ShexViolation(node);
                        ShexExplanation explanation = new ShexExplanation();
                        String shape_curie = getCurie(shapelabel);
                        explanation.setShape(shape_curie);
                        Set<ShexConstraint> unmet_constraints = getUnmetConstraints(focus_node_resource, shape_label, test_model, typing);
                        if (unmet_constraints != null) {
                            for (ShexConstraint constraint : unmet_constraints) {
                                explanation.addConstraint(constraint);
                                violation.addExplanation(explanation);
                            }
                        } else {
                            explanation.setErrorMessage("explanation computation timed out");
                            violation.addExplanation(explanation);
                        }
                        r.addViolation(violation);
                    } else if (status.equals(Status.NOTCOMPUTED)) {
                        //if any of these are not computed, there is a problem
                        String error = focus_node_id + " was not tested against " + shapelabel;
                        LOGGER.error(error);
                    }
                } else {
                    LOGGER.info("shex validation failed for node " + focus_node_resource.getURI());
                    all_good = false;
                    ShexViolation violation = new ShexViolation(focus_node_resource.getURI());
                    ShexExplanation explanation = new ShexExplanation();
                    explanation.setErrorMessage("Validating this node was canceled because it took more then " + timeout_mill + " milliseconds");
                    String shape_curie = getCurie(shapelabel);
                    explanation.setShape(shape_curie);
                    violation.addExplanation(explanation);
                    r.addViolation(violation);
                }
            }
        }
        if (all_good) {
            r.conformant = true;
        } else {
            r.conformant = false;
        }
        return r;
    }

    private Map<Resource, Set<String>> getShapesToTestForEachResource(Model test_model) {
        Map<Resource, Set<String>> node_shapes = new HashMap<Resource, Set<String>>();
        for (String shapelabel : GoQueryMap.keySet()) {
            //not quite the same pattern as the other shapes
            //TODO needs more work
            if (shapelabel.equals("http://purl.obolibrary.org/obo/go/shapes/AnnotatedEdge")) {
                continue;
            }
            //get the nodes in this model that SHOULD match the shape
            Set<Resource> focus_nodes = getFocusNodesBySparql(test_model, GoQueryMap.get(shapelabel));
            //shape_nodes.put(shapelabel, focus_nodes);

            for (Resource focus_node : focus_nodes) {
                Set<String> shapes = node_shapes.get(focus_node);
                if (shapes == null) {
                    shapes = new HashSet<String>();
                }
                shapes.add(shapelabel);
                node_shapes.put(focus_node, shapes);
            }
        }
        //prune to only test the most specific shapes
        //TODO - do it once up front
        Map<Resource, Set<String>> node_s_shapes = new HashMap<Resource, Set<String>>();
        for (Resource node : node_shapes.keySet()) {
            Set<String> shapes = node_shapes.get(node);
            Set<String> shapes_to_remove = new HashSet<String>();
            for (String shape1 : shapes) {
                Set<Resource> shape1_nodes = getFocusNodesBySparql(test_model, GoQueryMap.get(shape1));
                for (String shape2 : shapes) {
                    if (shape1.equals(shape2)) {
                        continue;
                    }
                    Set<Resource> shape2_nodes = getFocusNodesBySparql(test_model, GoQueryMap.get(shape2));
                    //if shape1 contains all of shape2 - e.g. mf would contain all transporter activity
                    if (shape1_nodes.containsAll(shape2_nodes)) {
                        //then remove shape1 from this resource (as shape2 is more specific).
                        shapes_to_remove.add(shape1);
                    }
                }
            }
            shapes.removeAll(shapes_to_remove);
            node_s_shapes.put(node, shapes);
        }
        return node_s_shapes;
    }

    private Typing runRefineWithTimeout(Graph shexy_graph) {
        final ExecutorService service = Executors.newSingleThreadExecutor();
        try {
            final Future<Typing> f = service.submit(() -> {
                RefineValidation refine = new RefineValidation(schema, shexy_graph);
                refine.validate();
                Typing all = refine.getTyping();
                return all;
            });
            Typing typing = f.get(timeout_mill, TimeUnit.MILLISECONDS);
            return typing;

        } catch (final TimeoutException e) {
            LOGGER.error("shex refine all validation took to long  ");
            service.shutdownNow();
            return null;
        } catch (InterruptedException e) {
            LOGGER.error("And we have Refine an interrupted exception: ");
            e.printStackTrace();
            service.shutdownNow();
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("And we have a Refine execution exception: ");
            e.printStackTrace();
            service.shutdownNow();
            return null;
        } finally {
            service.shutdown();
        }
    }

    public Typing validateNodeWithTimeout(RecursiveValidationWithMemorization shex_model_validator, Resource focus_node_resource, Label shape_label) {
        RDFTerm focus_node = null;
        String focus_node_id = "";
        if (focus_node_resource.isURIResource()) {
            focus_node = rdfFactory.createIRI(focus_node_resource.getURI());
            focus_node_id = focus_node_resource.getURI();
        } else {
            focus_node = rdfFactory.createBlankNode(focus_node_resource.getId().getLabelString());
            focus_node_id = focus_node_resource.getId().getLabelString();
        }
        //deal with curies for output
        String node = focus_node_id;
        node = getCurie(focus_node_id);
        //this can take a while - give up if it gets stuck
        //limit total time to avoid service death on some weird edge case
        final ExecutorService service = Executors.newSingleThreadExecutor();
        final RDFTerm test_node = focus_node;
        try {
            final Future<Typing> f = service.submit(() -> {
                boolean is_valid = shex_model_validator.validate(test_node, shape_label);
                if (is_valid) {
                    return shex_model_validator.getTyping();
                } else {
                    return null;
                }

            });
            Typing typing = f.get(timeout_mill, TimeUnit.MILLISECONDS);
            return typing;

        } catch (final TimeoutException e) {
            LOGGER.error("shex validation took to long for " + focus_node_resource);
            service.shutdownNow();
            return null;
        } catch (InterruptedException e) {
            LOGGER.error("And we have an interrupted exception: " + test_node + " " + shape_label);
            e.printStackTrace();
            service.shutdownNow();
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("And we have an execution exception: " + test_node + " " + shape_label);
            e.printStackTrace();
            service.shutdownNow();
            return null;
        } finally {
            service.shutdown();
        }
    }

    public static Set<Resource> getFocusNodesBySparql(Model model, String sparql) {
        Set<Resource> nodes = new HashSet<Resource>();
        QueryExecution qe = QueryExecutionFactory.create(sparql, model);
        ResultSet results = qe.execSelect();
        while (results.hasNext()) {
            QuerySolution qs = results.next();
            Resource node = qs.getResource("x");
            nodes.add(node);
        }
        qe.close();
        return nodes;
    }

    /**
     * Check each focus node for the use of any properties that don't appear in the shape
     * @param focus_nodes
     * @param model
     * @param shape_label
     * @return
     * @throws IOException
     */
    public Set<ShexViolation> checkForExtraProperties(Resource node_r, Model model, Label shape_label, Typing typing) throws IOException {
        Set<ShexViolation> violations = new HashSet<ShexViolation>();
        Set<String> allowed_properties = this.shape_expected_property_ranges.get(shape_label).keySet();
        Set<String> actual_properties = new HashSet<String>();
        Map<String, RDFNode> prop_value = new HashMap<String, RDFNode>(); //don't really care if there are multiple values, one will do.
        String sparql = "select distinct ?prop ?value where{ <" + node_r.getURI() + "> ?prop ?value }";
        QueryExecution qe = QueryExecutionFactory.create(sparql, model);
        ResultSet results = qe.execSelect();
        while (results.hasNext()) {
            QuerySolution qs = results.next();
            Resource prop = qs.getResource("prop");
            RDFNode value = qs.get("value");
            actual_properties.add(prop.getURI());
            prop_value.put(prop.getURI(), value);
        }
        qe.close();
        actual_properties.removeAll(allowed_properties);
        if (!actual_properties.isEmpty()) {
            ShexViolation extra = new ShexViolation(getCurie(node_r.getURI()));
            Set<ShexExplanation> explanations = new HashSet<ShexExplanation>();
            for (String prop : actual_properties) {
                String value = "value";
                boolean value_is_uri = false;
                if (prop_value.get(prop).isResource()) {
                    value = prop_value.get(prop).asResource().getURI();
                    value_is_uri = true;
                } else if (prop_value.get(prop).isLiteral()) {
                    value = prop_value.get(prop).asLiteral().getString();
                }
                ShexExplanation extra_explain = new ShexExplanation();
                extra_explain.setShape(getCurie(shape_label.stringValue()));
                Set<String> intended_range_shapes = new HashSet<String>();
                //For this CLOSED test, no shape fits in intended.  Any use of the property here would be incorrect.
                intended_range_shapes.add("owl:Nothing");
                Set<String> node_types = getNodeTypes(model, node_r.getURI());
                Set<String> object_types = null;
                //TODO consider here.  extra info but not really meaningful - anything in the range would be wrong.
                Set<String> matched_range_shapes = null;
                if (value_is_uri) {
                    object_types = getNodeTypes(model, value);
                    RDFTerm node = rdfFactory.createIRI(value);
                    matched_range_shapes = getAllMatchedShapes(node, typing);
                }
                String report_prop = getCurie(prop);
                ShexConstraint c = new ShexConstraint(value, report_prop, intended_range_shapes, node_types, object_types);
                c.setMatched_range_shapes(matched_range_shapes);
                Set<ShexConstraint> cs = new HashSet<ShexConstraint>();
                cs.add(c);
                extra_explain.setConstraints(cs);
                explanations.add(extra_explain);
            }
            extra.setExplanations(explanations);
            violations.add(extra);
        }
        return violations;

    }

    public Model enrichSuperClasses(Model model) throws IOException {
        LOGGER.info("model size before reasoner expansion: " + model.size());
        String getOntTerms =
                "PREFIX owl: <http://www.w3.org/2002/07/owl#> "
                        + "SELECT DISTINCT ?term " +
                        "        WHERE { " +
                        "        {     " +
                        "        ?ind a owl:NamedIndividual . " +
                        "        ?ind a ?term . " +
                        "        } " +
                        "        UNION" +
                        "        {" +
                        "        ?term a owl:Class ." +
                        "        }" +
                        "        FILTER(?term != owl:NamedIndividual)" +
                        "        FILTER(isIRI(?term)) ." +
                        "        }";
        String terms = "";
        Set<String> term_set = new HashSet<String>();
        try {
            QueryExecution qe = QueryExecutionFactory.create(getOntTerms, model);
            ResultSet results = qe.execSelect();

            while (results.hasNext()) {
                QuerySolution qs = results.next();
                Resource term = qs.getResource("term");
                terms += ("<" + term.getURI() + "> ");
                term_set.add(term.getURI());
            }
            qe.close();
        } catch (QueryParseException e) {
            e.printStackTrace();
        }
        if (getGo_lego_repo() != null) {
            Map<String, Set<String>> term_parents = getGo_lego_repo().getSuperClassMap(term_set);
            for (String term : term_set) {
                Resource child = model.createResource(term);
                for (String parent_class : term_parents.get(term)) {
                    Resource parent = model.createResource(parent_class);
                    model.add(model.createStatement(child, org.apache.jena.vocabulary.RDFS.subClassOf, child));
                    model.add(model.createStatement(child, org.apache.jena.vocabulary.RDFS.subClassOf, parent));
                    model.add(model.createStatement(child, org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL.Class));
                }
            }
        }
        //		if(tbox_reasoner!=null) {
        //			for(String term : term_set) {
        //				OWLClass c =
        //						tbox_reasoner.
        //						getRootOntology().
        //						getOWLOntologyManager().
        //						getOWLDataFactory().getOWLClass(IRI.create(term));
        //				Resource child = model.createResource(term);
        //				Set<OWLClass> supers = tbox_reasoner.getSuperClasses(c, false).getFlattened();
        //				for(OWLClass parent_class : supers) {
        //					Resource parent = model.createResource(parent_class.getIRI().toString());
        //					model.add(model.createStatement(child, org.apache.jena.vocabulary.RDFS.subClassOf, child));
        //					model.add(model.createStatement(child, org.apache.jena.vocabulary.RDFS.subClassOf, parent));
        //					model.add(model.createStatement(child, org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.OWL.Class));
        //				}
        //			}
        //		}
        //		else {
        //			String superQuery = ""
        //					+ "PREFIX owl: <http://www.w3.org/2002/07/owl#> "
        //					+ "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
        //					+ "CONSTRUCT { " +
        //					"        ?term rdfs:subClassOf ?superclass ." +
        //					"        ?term a owl:Class ." +
        //					"        }" +
        //					"        WHERE {" +
        //					"        VALUES ?term { "+terms+" } " +
        //					"        ?term rdfs:subClassOf* ?superclass ." +
        //					"        FILTER(isIRI(?superclass)) ." +
        //					"        }";
        //
        //			Query query = QueryFactory.create(superQuery);
        //			try (
        //					QueryExecution qexec = QueryExecutionFactory.sparqlService(endpoint, query) ) {
        //				qexec.execConstruct(model);
        //				qexec.close();
        //			} catch(QueryParseException e){
        //				e.printStackTrace();
        //			}
        //		}
        //LOGGER.info("model size after reasoner expansion: "+model.size());
        return model;
    }

    public Set<String> getNodeTypes(Model model, String node_uri) throws IOException {

        String getOntTerms =
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
                        + "PREFIX owl: <http://www.w3.org/2002/07/owl#> "
                        + "SELECT DISTINCT ?type ?is_negated " +
                        "        WHERE { " +
                        "{ <" + node_uri + "> rdf:type ?type . FILTER(isIRI(?type)) VALUES ?is_negated { false } }" +
                        "UNION" +
                        "{ <" + node_uri + "> rdf:type ?blank . ?blank owl:complementOf ?type . FILTER(isBlank(?blank) && isIRI(?type)) VALUES ?is_negated { true } }" +
                        "FILTER(?type != owl:NamedIndividual)" +
                        "        }";
        Set<String> types = new HashSet<String>();
        try {
            QueryExecution qe = QueryExecutionFactory.create(getOntTerms, model);
            ResultSet results = qe.execSelect();
            while (results.hasNext()) {
                QuerySolution qs = results.next();
                Resource type = qs.getResource("type");
                boolean isNegated = qs.getLiteral("is_negated").getBoolean();
                String typeCurie = getCurie(type.getURI());
                if (isNegated) {
                    types.add("NOT(" + typeCurie + ")");
                } else types.add(typeCurie);
                //				OWLClass t = tbox_reasoner.getRootOntology().getOWLOntologyManager().getOWLDataFactory().getOWLClass(IRI.create(type.getURI()));
                //				for(OWLClass p : tbox_reasoner.getSuperClasses(t, false).getFlattened()) {
                //					String type_curie = getCurie(p.getIRI().toString());
                //					types.add(type_curie);
                //				}
                //this slows it down a lot...
                //should not be needed
                //Set<String> supers = getGo_lego_repo().getSuperClasses(type.getURI());
                //for(String s : supers) {
                //	String type_curie = getCurie(s);
                //	types.add(type_curie);
                //}

            }
            qe.close();
        } catch (QueryParseException e) {
            LOGGER.error(getOntTerms);
            e.printStackTrace();
        }

        return types;
    }

    /**
     * We no there is a problem with the focus node and the shape here.  This tries to figure out the constraints that caused the problem and provide some explanation.
     * @param focus_node
     * @param shape_label
     * @param model
     * @param typing
     * @return
     * @throws IOException
     */
    private Set<ShexConstraint> getUnmetConstraints(Resource focus_node, Label shape_label, Model model, Typing typing) throws IOException {
        Set<ShexConstraint> unmet_constraints = new HashSet<ShexConstraint>();
        Set<String> node_types = getNodeTypes(model, focus_node.getURI());
        Map<String, Set<String>> expected_property_ranges = shape_expected_property_ranges.get(shape_label);
        //get a map from properties to actual shapes of the asserted objects
        //		JenaRDF jr = new JenaRDF();
        //		JenaGraph shexy_graph = jr.asGraph(model);
        //		RecursiveValidationWithMemorization shex_model_validator = new RecursiveValidationWithMemorization(schema, shexy_graph);

        //get the focus node in the rdf model
        //check for assertions with properties in the target shape
        for (String prop_uri : expected_property_ranges.keySet()) {
            Property prop = model.getProperty(prop_uri);
            Interval cardinality = shape_expected_property_cardinality.get(shape_label).get(prop_uri);
            //checking on objects of this property for the problem node.
            int n_objects = 0;
            for (StmtIterator i = focus_node.listProperties(prop); i.hasNext(); ) {
                while (i.hasNext()) {
                    n_objects++;
                    RDFNode obj = i.nextStatement().getObject();
                    //check the computed shapes for this individual
                    if (!obj.isResource()) {
                        continue;
                        //no checks on literal values at this time
                    } else if (prop_uri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type") && obj.asResource().getURI().equals("http://www.w3.org/2002/07/owl#NamedIndividual")) {
                        continue; //ignore type owl individual
                    }
                    RDFTerm range_obj = rdfFactory.createIRI(obj.asResource().getURI());
                    //does it hit any allowable shapes?
                    boolean good = false;
                    //TODO many property ranges are MISSING from previous step
                    //e.g. any OR will not show up here.
                    Set<String> expected_ranges = expected_property_ranges.get(prop_uri);
                    for (String target_shape_uri : expected_ranges) {
                        if (target_shape_uri.equals(".")) {
                            //anything is fine
                            good = true;
                            //break;
                        } else if (target_shape_uri.trim().equals("<http://www.w3.org/2001/XMLSchema#string>")) {
                            //ignore syntax type checking for now
                            good = true;
                            //break;
                        }
                        Label target_shape_label = new Label(rdfFactory.createIRI(target_shape_uri));
                        //		Typing typing = validateNodeWithTimeout(shex_model_validator, obj.asResource(), shape_label);
                        if (typing != null) {
                            //capture the result
                            //Typing shape_test = shex_model_validator.getTyping();
                            //Pair<RDFTerm, Label> p = new Pair<RDFTerm, Label>(range_obj, target_shape_label);
                            //Status r = shape_test.getStatusMap().get(p);
                            Status r = typing.getStatus(range_obj, target_shape_label);
                            if (r != null && r.equals(Status.CONFORMANT)) {
                                good = true;
                                //break;
                            }
                        } else {
                            good = false;
                        }
                    }
                    if (!good) { //add violated range constraint to explanation
                        if (obj.isURIResource()) {
                            String object = obj.toString();
                            Set<String> object_types = getNodeTypes(model, obj.toString());

                            String property = prop.toString();
                            object = getCurie(object);
                            property = getCurie(property);
                            Set<String> expected = new HashSet<String>();
                            for (String e : expected_property_ranges.get(prop_uri)) {
                                String curie_e = getCurie(e);
                                expected.add(curie_e);
                            }
                            ShexConstraint constraint = new ShexConstraint(object, property, expected, node_types, object_types);
                            //return all shapes that are matched by this node for explanation
                            Set<String> obj_matched_shapes = getAllMatchedShapes(range_obj, typing);
                            constraint.setMatched_range_shapes(obj_matched_shapes);
                            unmet_constraints.add(constraint);
                        } else {
                            ShexConstraint constraint = new ShexConstraint(obj.toString(), getCurie(prop.toString()), null, node_types, null);
                            //return all shapes that are matched by this node for explanation
                            Set<String> obj_matched_shapes = getAllMatchedShapes(range_obj, typing);
                            constraint.setMatched_range_shapes(obj_matched_shapes);
                            unmet_constraints.add(constraint);
                        }
                    }
                }
            }
            //check for cardinality violations
            if (!prop_uri.contentEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) { //skip types - should always allow multiple..
                if (!cardinality.contains(n_objects)) {
                    System.out.println("cardinality violation!");
                    System.out.println("problem node " + focus_node);
                    System.out.println("prop " + prop);
                    System.out.println("Intended Interval " + cardinality.toString());
                    System.out.println("Actual " + n_objects);
                    ShexConstraint constraint = new ShexConstraint(getCurie(prop.toString()), cardinality.toString(), n_objects);
                    unmet_constraints.add(constraint);
                }
            }
        }

        return unmet_constraints;
    }

    public Set<String> getAllMatchedShapes(RDFTerm value, Typing typing) {
        Set<Label> all_shapes_in_schema = getAllShapesInSchema();
        Set<String> obj_matched_shapes = new HashSet<String>();
        for (Label target_shape_label : all_shapes_in_schema) {
            Pair<RDFTerm, Label> p = new Pair<RDFTerm, Label>(value, target_shape_label);
            Status r = typing.getStatusMap().get(p);
            if (r.equals(Status.CONFORMANT)) {
                obj_matched_shapes.add(getCurie(target_shape_label.stringValue()));
            }
        }
        return obj_matched_shapes;
    }

    public Set<String> getAllMatchedShapes(RDFTerm value, RecursiveValidationWithMemorization shex_model_validator) {
        Set<Label> all_shapes_in_schema = getAllShapesInSchema();
        Set<String> obj_matched_shapes = new HashSet<String>();
        for (Label target_shape_label : all_shapes_in_schema) {
            shex_model_validator.validate(value, target_shape_label);
            Typing shape_test = shex_model_validator.getTyping();
            Pair<RDFTerm, Label> p = new Pair<RDFTerm, Label>(value, target_shape_label);
            Status r = shape_test.getStatusMap().get(p);
            if (r.equals(Status.CONFORMANT)) {
                obj_matched_shapes.add(getCurie(target_shape_label.stringValue()));
            }
        }
        return obj_matched_shapes;
    }

    public Set<String> getAllMatchedShapes(String node_uri, RecursiveValidationWithMemorization shex_model_validator) {
        RDFTerm range_obj = rdfFactory.createIRI(node_uri);
        return getAllMatchedShapes(range_obj, shex_model_validator);
    }


    private Set<Label> getAllShapesInSchema() {
        return schema.getRules().keySet();
    }


    public String getCurie(String uri) {
        String curie = uri;
        if (curieHandler != null) {
            curie = curieHandler.getCuri(IRI.create(uri));
        }
        return curie;
    }


    public Map<String, Set<String>> getPropertyRangeMap(Label rootshapelabel, ShapeExpr expr, Map<String, Set<String>> prop_range) {
        if (prop_range == null) {
            prop_range = new HashMap<String, Set<String>>();
        }
        String explanation = "";
        if (expr instanceof ShapeAnd) {
            explanation += "And\n";
            ShapeAnd andshape = (ShapeAnd) expr;
            for (ShapeExpr subexp : andshape.getSubExpressions()) {
                //boolean is_closed = subexp.closed;
                prop_range = getPropertyRangeMap(rootshapelabel, subexp, prop_range);
                explanation += subexp + " ";
            }
        } else if (expr instanceof ShapeOr) {
            explanation += "Or\n";
            ShapeOr orshape = (ShapeOr) expr;
            for (ShapeExpr subexp : orshape.getSubExpressions()) {
                explanation += subexp;
                //explainShape(subexp, explanation);
            }
        } else if (expr instanceof ShapeExprRef) {
            ShapeExprRef ref = (ShapeExprRef) expr;
            ShapeExpr ref_expr = ref.getShapeDefinition();
            prop_range = getPropertyRangeMap(rootshapelabel, ref_expr, prop_range);
            //not in the rdf model - this is a match of this expr on a shape
            //e.g. <http://purl.obolibrary.org/obo/go/shapes/GoCamEntity>
            explanation += "\t\tis a: " + ((ShapeExprRef) expr).getLabel() + "\n";
        } else if (expr instanceof Shape) {
            Shape shape = (Shape) expr;
            TripleExpr texp = shape.getTripleExpression();
            prop_range = getPropertyRangeMap(rootshapelabel, texp, prop_range);
        } else if (expr instanceof NodeConstraint) {
            NodeConstraint nc = (NodeConstraint) expr;
            explanation += "\t\tnode constraint " + nc.toPrettyString();
        } else {
            explanation += " Not sure what is: " + expr;
        }
        return prop_range;
    }


    public Map<String, Set<String>> getPropertyRangeMap(Label rootshapelabel, TripleExpr texp, Map<String, Set<String>> prop_range) {
        if (prop_range == null) {
            prop_range = new HashMap<String, Set<String>>();
        }
        if (texp instanceof TripleConstraint) {
            TripleConstraint tcon = (TripleConstraint) texp;
            TCProperty tprop = tcon.getProperty();
            ShapeExpr range = tcon.getShapeExpr();
            String prop_uri = tprop.getIri().toString();
            Set<String> ranges = prop_range.get(prop_uri);
            if (ranges == null) {
                ranges = new HashSet<String>();
            }
            ranges.addAll(getShapeExprRefs(rootshapelabel, range, ranges));
            prop_range.put(prop_uri, ranges);
            //Map<Label, Map<String, Integer>> shape_expected_property_cardinality
            Map<String, Interval> property_cardinality = shape_expected_property_cardinality.get(rootshapelabel);
            if (property_cardinality == null) {
                property_cardinality = new HashMap<String, Interval>();
            }
            property_cardinality.put(prop_uri, tripexprlabel_cardinality.get(texp.getId()));
            shape_expected_property_cardinality.put(rootshapelabel, property_cardinality);
        } else if (texp instanceof EachOf) {
            EachOf each = (EachOf) texp;
            for (TripleExpr eachtexp : each.getSubExpressions()) {
                if (!texp.equals(eachtexp)) {
                    prop_range = getPropertyRangeMap(rootshapelabel, eachtexp, prop_range);
                }
            }
        } else if (texp instanceof RepeatedTripleExpression) {
            RepeatedTripleExpression rep = (RepeatedTripleExpression) texp;
            TripleExpr t = rep.getSubExpression();
            Label texp_label = t.getId();
            tripexprlabel_cardinality.put(texp_label, rep.getCardinality());
            prop_range = getPropertyRangeMap(rootshapelabel, t, prop_range);
        } else if (texp instanceof TripleExprRef) {
            TripleExprRef ref = (TripleExprRef) texp;
            prop_range = getPropertyRangeMap(rootshapelabel, ref.getTripleExp(), prop_range);
        } else {
            System.out.println("\tlost again here on " + texp);
        }
        return prop_range;
    }

    private Set<String> getShapeExprRefs(Label rootshapelabel, ShapeExpr expr, Set<String> shape_refs) {
        if (shape_refs == null) {
            shape_refs = new HashSet<String>();
        }
        if (expr instanceof ShapeExprRef) {
            shape_refs.add(((ShapeExprRef) expr).getLabel().stringValue());
        } else if (expr instanceof ShapeAnd) {
            ShapeAnd andshape = (ShapeAnd) expr;
            System.out.println("currently ignoring And shape in range: " + expr);
            //			for(ShapeExpr subexp : andshape.getSubExpressions()) {
            //				shape_refs = getShapeExprRefs(subexp, shape_refs);
            //			}
        } else if (expr instanceof ShapeOr) {
            ShapeOr orshape = (ShapeOr) expr;
            for (ShapeExpr subexp : orshape.getSubExpressions()) {
                shape_refs = getShapeExprRefs(rootshapelabel, subexp, shape_refs);
            }
        } else if (expr instanceof NodeConstraint) {
            String reference_string = ((NodeConstraint) expr).toPrettyString();
            reference_string = reference_string.replace("<", "");
            reference_string = reference_string.replace(">", "");
            reference_string = reference_string.replace("[", "");
            reference_string = reference_string.replace("]", "");
            reference_string = reference_string.trim();
            shape_refs.add(reference_string);
        } else {
            System.out.println("currently ignoring " + expr);
        }
        return shape_refs;
    }


    public static String getModelTitle(Model model) {
        String model_title = null;
        String q = "select ?cam ?title where {"
                + "GRAPH ?cam {  "
                + "?cam <http://purl.org/dc/elements/1.1/title> ?title"
                + "}}";
        //	+ "?cam <"+DC.description.getURI()+"> ?title }";
        QueryExecution qe = QueryExecutionFactory.create(q, model);
        ResultSet results = qe.execSelect();
        if (results.hasNext()) {
            QuerySolution qs = results.next();
            Resource model_id_resource = qs.getResource("cam");
            Literal title = qs.getLiteral("title");
            model_title = title.getString();
        }
        qe.close();
        return model_title;
    }

    public BlazegraphOntologyManager getGo_lego_repo() {
        return go_lego_repo;
    }

    public void setGo_lego_repo(BlazegraphOntologyManager go_lego_repo) {
        this.go_lego_repo = go_lego_repo;
    }
}
