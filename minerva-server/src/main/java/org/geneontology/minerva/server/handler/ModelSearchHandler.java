package org.geneontology.minerva.server.handler;

import com.google.gson.annotations.SerializedName;
import org.apache.commons.io.IOUtils;
import org.geneontology.minerva.BlazegraphMolecularModelManager;
import org.geneontology.minerva.BlazegraphOntologyManager;
import org.geneontology.minerva.MolecularModelManager.UnknownIdentifierException;
import org.geneontology.minerva.curie.CurieHandler;
import org.openrdf.query.*;
import org.openrdf.repository.RepositoryException;
import org.semanticweb.owlapi.model.IRI;

import javax.annotation.Nullable;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Respond to queries for models in the running blazegraph instance backing minerva
 * Uses Jersey + JSONP
 */
@Path("/search/models")
public class ModelSearchHandler {

    private final BlazegraphMolecularModelManager<?> m3;
    private final BlazegraphOntologyManager go_lego;

    /**
     *
     */
    public ModelSearchHandler(BlazegraphMolecularModelManager<?> m3) {
        this.m3 = m3;
        this.go_lego = m3.getGolego_repo();
    }

    public class ModelSearchResult {
        private Integer n;
        private LinkedHashSet<ModelMeta> models;
        private String message;
        private String error;
        private String sparql;

        public Integer getN() {
            return n;
        }

        public void setN(Integer n) {
            this.n = n;
        }

        public LinkedHashSet<ModelMeta> getModels() {
            return models;
        }

        public void setModels(LinkedHashSet<ModelMeta> models) {
            this.models = models;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getSparql() {
            return sparql;
        }

        public void setSparql(String sparql) {
            this.sparql = sparql;
        }


    }

    static public class ModelMeta {
        private String id;
        private String date;
        private String title;
        private String state;
        @SerializedName("conforms-to-gpad")
        private Boolean conformsToGPAD;
        private Set<String> contributors;
        private Set<String> groups;
        private HashMap<String, Set<String>> query_match;

        @SerializedName("modified-p")
        private boolean modified;

        public ModelMeta(String id, String date, String title, String state, @Nullable Boolean conformsToGPAD, Set<String> contributors, Set<String> groups, boolean modified) {
            this.id = id;
            this.date = date;
            this.title = title;
            this.state = state;
            this.conformsToGPAD = conformsToGPAD;
            this.contributors = contributors;
            this.groups = groups;
            this.modified = modified;
            query_match = new HashMap<String, Set<String>>();
        }


        public boolean isModified() {
            return modified;
        }


        public void setModified(boolean modified) {
            this.modified = modified;
        }


        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        @Nullable
        public Boolean getConformsToGPAD() {
            return this.conformsToGPAD;
        }

        public void setConformsToGPAD(Boolean conforms) {
            this.conformsToGPAD = conforms;
        }

        public Set<String> getContributors() {
            return contributors;
        }

        public void setContributors(Set<String> contributors) {
            this.contributors = contributors;
        }

        public Set<String> getGroups() {
            return groups;
        }

        public void setGroups(Set<String> groups) {
            this.groups = groups;
        }

        public HashMap<String, Set<String>> getQuery_match() {
            return query_match;
        }

        public void setQuery_match(HashMap<String, Set<String>> query_match) {
            this.query_match = query_match;
        }


    }


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ModelSearchResult searchGet(
            @QueryParam("taxon") Set<String> taxa,
            @QueryParam("gp") Set<String> gene_product_class_uris,
            @QueryParam("term") Set<String> terms,
            @QueryParam("expand") String expand,
            @QueryParam("pmid") Set<String> pmids,
            @QueryParam("title") String title,
            @QueryParam("state") Set<String> state,
            @QueryParam("contributor") Set<String> contributor,
            @QueryParam("group") Set<String> group,
            @QueryParam("exactdate") String exactdate,
            @QueryParam("date") String date,
            @QueryParam("dateend") String datend,
            @QueryParam("offset") int offset,
            @QueryParam("limit") int limit,
            @QueryParam("count") String count,
            @QueryParam("debug") String debug,
            @QueryParam("id") Set<String> id
    ) {
        ModelSearchResult result = new ModelSearchResult();
        result = search(taxa, gene_product_class_uris, terms, expand, pmids, title, state, contributor, group, exactdate, date, datend, offset, limit, count, debug, id);
        return result;
    }

    //examples
    //http://127.0.0.1:6800/search/?
    //?gp=http://identifiers.org/uniprot/P15822-3
    //?term=http://purl.obolibrary.org/obo/GO_0003677
    //
    //
    //?gp=http://identifiers.org/mgi/MGI:1328355
    //&gp=http://identifiers.org/mgi/MGI:87986
    //&term=http://purl.obolibrary.org/obo/GO_0030968
    //&title=mouse
    //&pmid=PMID:19911006
    //&state=development&state=review {development, production, closed, review, delete} or operator
    //&count
    //127.0.0.1:6800/search/?contributor=http://orcid.org/0000-0002-1706-4196
    public ModelSearchResult search(Set<String> taxa,
                                    Set<String> gene_product_ids, Set<String> terms, String expand, Set<String> pmids,
                                    String title_search, Set<String> state_search, Set<String> contributor_search, Set<String> group_search,
                                    String exactdate, String date_search, String datend,
                                    int offset, int limit, String count, String debug, Set<String> id) {
        ModelSearchResult r = new ModelSearchResult();
        Set<String> go_type_ids = new HashSet<String>();
        Set<String> gene_type_ids = new HashSet<String>();
        if (gene_product_ids != null) {
            gene_type_ids.addAll(gene_product_ids);
        }
        if (terms != null) {
            go_type_ids.addAll(terms);
        }
        CurieHandler curie_handler = m3.getCuriHandler();
        Set<String> go_type_uris = new HashSet<String>();
        Set<String> gene_type_uris = new HashSet<String>();
        for (String curi : go_type_ids) {
            if (curi.startsWith("http")) {
                go_type_uris.add(curi);
            } else {
                try {
                    IRI iri = curie_handler.getIRI(curi);
                    if (iri != null) {
                        go_type_uris.add(iri.toString());
                    }
                } catch (UnknownIdentifierException e) {
                    r.error += e.getMessage() + " \n ";
                    e.printStackTrace();
                    return r;
                }
            }
        }
        for (String curi : gene_type_ids) {
            if (curi.startsWith("http")) {
                gene_type_uris.add(curi);
            } else {
                try {
                    IRI iri = curie_handler.getIRI(curi);
                    if (iri != null) {
                        gene_type_uris.add(iri.toString());
                    }
                } catch (UnknownIdentifierException e) {
                    r.error += e.getMessage() + " \n ";
                    e.printStackTrace();
                    return r;
                }
            }
        }
        Map<String, ModelMeta> id_model = new LinkedHashMap<String, ModelMeta>();
        String sparql = "";
        try {
            sparql = IOUtils.toString(ModelSearchHandler.class.getResourceAsStream("/ModelSearchQueryTemplate.rq"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Map<String, String> ind_return = new HashMap<String, String>();
        String ind_return_list = ""; //<ind_return_list>
        String types = ""; //<types>
        int n = 0;
        for (String type_uri : gene_type_uris) {
            n++;
            ind_return.put("?ind" + n, type_uri);
            ind_return_list = ind_return_list + " (GROUP_CONCAT(?ind" + n + " ; separator=\" \") AS ?inds" + n + ")";
            types = types + "?ind" + n + " rdf:type <" + type_uri + "> . \n";
        }
        if (expand != null) {
            for (String go_type_uri : go_type_uris) {
                n++;
                ind_return.put("?ind" + n, go_type_uri);
                ind_return_list = ind_return_list + " (GROUP_CONCAT(?ind" + n + " ; separator=\" \") AS ?inds" + n + ")";
                String expansion = "VALUES ?term" + n + " { ";
                try {
                    Set<String> subclasses = go_lego.getAllSubClasses(go_type_uri);
                    for (String sub : subclasses) {
                        expansion += "<" + sub + "> \n";
                    }
                    expansion += "} . \n";
                    types = types + " " + expansion + " ?ind" + n + " rdf:type ?term" + n + " . \n";
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } else {
            for (String go_type_uri : go_type_uris) {
                n++;
                ind_return.put("?ind" + n, go_type_uri);
                ind_return_list = ind_return_list + " (GROUP_CONCAT(?ind" + n + " ; separator=\" \") AS ?inds" + n + ")";
                types = types + "?ind" + n + " rdf:type <" + go_type_uri + "> . \n";
            }
        }
        String id_constraint = "";
        if (id != null && id.size() > 0) {
            String id_list = "";
            for (String mid : id) {
                if (!mid.contains("http")) {
                    String[] curie = mid.split(":");
                    if (curie != null && curie.length == 2) {
                        mid = "http://model.geneontology.org/" + curie[1];
                    }
                    //TODO figure this out and add it to standard curie collection
                    //				try {
                    //					IRI iri = curie_handler.getIRI(id);
                    //					id = iri.toString();
                    //				} catch (UnknownIdentifierException e) {
                    //					// TODO Auto-generated catch block
                    //					e.printStackTrace();
                    //				}
                }
                id_list += "<" + mid + "> ";
            }
            id_constraint = " values ?id { " + id_list + " } ";
        }
        String pmid_constraints = ""; //<pmid_constraints>
        if (pmids != null) {
            for (String pmid : pmids) {
                n++;
                ind_return.put("?ind" + n, pmid);
                ind_return_list = ind_return_list + " (GROUP_CONCAT(?ind" + n + " ; separator=\" \") AS ?inds" + n + ")";
                pmid_constraints = pmid_constraints + "?ind" + n + " <http://purl.org/dc/elements/1.1/source> ?pmid FILTER (?pmid=\"" + pmid + "\"^^xsd:string) .\n";
            }
        }
        String taxa_constraint = "";
        if (taxa != null && !taxa.isEmpty()) {
            for (String taxon : taxa) {
                if (taxon.startsWith("NCBITaxon:")) {
                    taxon = taxon.replace(":", "_");
                    taxon = "http://purl.obolibrary.org/obo/" + taxon;
                } else if (!taxon.startsWith("http://purl.obolibrary.org/obo/NCBITaxon_")) {
                    taxon = "http://purl.obolibrary.org/obo/NCBITaxon_" + taxon;
                }
                taxa_constraint += "?id <" + BlazegraphOntologyManager.in_taxon_uri + "> <" + taxon + "> . \n";
            }
        }


        //		if(taxa!=null&&!taxa.isEmpty()) {
        //			String model_filter =  " VALUES ?id { \n";
        //			for(String taxon : taxa) {
        //				if(taxon.startsWith("NCBITaxon:")) {
        //					taxon = taxon.replace(":", "_");
        //					taxon = "http://purl.obolibrary.org/obo/"+taxon;
        //				}
        //				else if(!taxon.startsWith("http://purl.obolibrary.org/obo/NCBITaxon_")) {
        //					taxon = "http://purl.obolibrary.org/obo/NCBITaxon_"+taxon;
        //				}
        //				Set<String> models = taxon_models.get(taxon);
        //				if(models!=null) {
        //					for(String model : models) {
        //						model_filter+="<"+model+"> \n";
        //					}
        //				}
        //			}
        //			model_filter += "} . \n";
        //			taxa_constraint = model_filter;
        //		}
        String title_search_constraint = "";
        if (title_search != null) {
            title_search_constraint = "?title <http://www.bigdata.com/rdf/search#search> \"" + title_search + "\" .\n";
            if (!title_search.contains("*")) {
                title_search_constraint += " ?title <http://www.bigdata.com/rdf/search#matchAllTerms> \"" + "true" + "\" . \n";
            }
            //			if(exact_match) {
            //				title_search_constraint+=" ?title <http://www.bigdata.com/rdf/search#matchExact>  \""+"true"+"\" . \n";
            //			}
        }
        String state_search_constraint = "";
        if (state_search != null && state_search.size() > 0) {
            String allowed_states = "";
            int c = 0;
            for (String s : state_search) {
                c++;
                allowed_states += "\"" + s + "\"";
                if (c < state_search.size()) {
                    allowed_states += ",";
                }
            }
            // FILTER (?state IN ("production", , "development", "review", "closed", "delete" ))
            state_search_constraint = "FILTER (?state IN (" + allowed_states + ")) . \n";
        }
        String contributor_search_constraint = "";
        if (contributor_search != null && contributor_search.size() > 0) {
            String allowed_contributors = "";
            int c = 0;
            for (String contributor : contributor_search) {
                c++;
                allowed_contributors += "\"" + contributor + "\"";
                if (c < contributor_search.size()) {
                    allowed_contributors += ",";
                }
            }
            contributor_search_constraint =
                    " ?id <http://purl.org/dc/elements/1.1/contributor> ?test_contributor . \n"
                            + " FILTER (?test_contributor IN (" + allowed_contributors + ")) . \n";
        }
        String group_search_constraint = "";
        if (group_search != null && group_search.size() > 0) {
            String allowed_group = "";
            int c = 0;
            for (String group : group_search) {
                c++;
                allowed_group += "\"" + group + "\"";
                if (c < group_search.size()) {
                    allowed_group += ",";
                }
            }
            group_search_constraint = " ?id <http://purl.org/pav/providedBy> ?test_group . \n"
                    + "FILTER (?test_group IN (" + allowed_group + ")) . \n";
        }
        String date_constraint = "";
        if (exactdate != null && exactdate.length() == 10) {
            date_constraint = "FILTER (?date = '" + exactdate + "') \n";
        } else if (date_search != null && date_search.length() == 10) {
            //e.g. 2019-06-26
            date_constraint = "FILTER (?date > '" + date_search + "') \n";
            if (datend != null && datend.length() == 10) {
                date_constraint = "FILTER (?date > '" + date_search + "' && ?date < '" + datend + "') \n";
            }
        }
        String offset_constraint = "";
        if (offset != 0) {
            offset_constraint = "OFFSET " + offset + "\n";
        }
        String limit_constraint = "";
        if (limit != 0) {
            limit_constraint = "LIMIT " + limit + "\n";
        }
        if (offset == 0 && limit == 0) {
            limit_constraint = "LIMIT 1000\n";
        }
        //default group by
        String group_by_constraint = "GROUP BY ?id";
        //default return block
        //TODO investigate need to add DISTINCT to GROUP_CONCAT here
        String return_block = "?id (MIN(?date) AS ?mindate) (MIN(?title) AS ?mintitle) (MIN(?state) AS ?minstate) (MIN(?conformsToGPAD) AS ?minConformsToGPAD) <ind_return_list> (GROUP_CONCAT(DISTINCT ?contributor;separator=\";\") AS ?contributors) (GROUP_CONCAT(DISTINCT ?group;separator=\";\") AS ?groups)";
        if (count != null) {
            return_block = "(count(distinct ?id) as ?count)";
            limit_constraint = "";
            offset_constraint = "";
            group_by_constraint = "";
        }
        sparql = sparql.replaceAll("<return_block>", return_block);
        sparql = sparql.replaceAll("<id_constraint>", id_constraint);
        sparql = sparql.replaceAll("<group_by_constraint>", group_by_constraint);
        sparql = sparql.replaceAll("<ind_return_list>", ind_return_list);
        sparql = sparql.replaceAll("<types>", types);
        sparql = sparql.replaceAll("<pmid_constraints>", pmid_constraints);
        sparql = sparql.replaceAll("<title_constraint>", title_search_constraint);
        sparql = sparql.replaceAll("<state_constraint>", state_search_constraint);
        sparql = sparql.replaceAll("<contributor_constraint>", contributor_search_constraint);
        sparql = sparql.replaceAll("<group_constraint>", group_search_constraint);
        sparql = sparql.replaceAll("<date_constraint>", date_constraint);
        sparql = sparql.replaceAll("<limit_constraint>", limit_constraint);
        sparql = sparql.replaceAll("<offset_constraint>", offset_constraint);
        sparql = sparql.replaceAll("<taxa_constraint>", taxa_constraint);
        if (debug != null) {
            r.sparql = sparql;
        } else {
            r.sparql = "add 'debug' parameter to see sparql request";
        }
        TupleQueryResult result;
        try {
            result = (TupleQueryResult) m3.executeSPARQLQuery(sparql, 1000);
        } catch (MalformedQueryException | QueryEvaluationException | RepositoryException e) {
            if (e instanceof MalformedQueryException) {
                r.message = "Malformed Query";
            } else if (e instanceof QueryEvaluationException) {
                r.message = "Query Evaluation Problem - probably a time out";
            } else if (e instanceof RepositoryException) {
                r.message = "Repository Exception";
            }
            r.error = e.getMessage();
            e.printStackTrace();
            return r;
        }
        String n_count = null;
        try {
            while (result.hasNext()) {
                BindingSet bs = result.next();
                if (count != null) {
                    n_count = bs.getBinding("count").getValue().stringValue();
                } else {
                    //model meta
                    String model_iri_string = bs.getBinding("id").getValue().stringValue();
                    IRI model_iri = IRI.create(model_iri_string);
                    String model_curie = null;
                    try {
                        model_curie = curie_handler.getCuri(IRI.create(model_iri_string));
                        if (model_curie == null) {
                            model_curie = model_iri_string;
                        }
                    } catch (Exception e) {
                        r.error += e.getMessage() + " \n ";
                        e.printStackTrace();
                        return r;
                    }
                    String date = bs.getBinding("mindate").getValue().stringValue();
                    String title = bs.getBinding("mintitle").getValue().stringValue();
                    String contribs = bs.getBinding("contributors").getValue().stringValue();
                    //optional values (some are empty)
                    Binding state_binding = bs.getBinding("minstate");
                    String state = "";
                    if (state_binding != null) {
                        state = state_binding.getValue().stringValue();
                    }
                    Binding conformanceBinding = bs.getBinding("minConformsToGPAD");
                    Boolean conformance = null;
                    if (conformanceBinding != null) {
                        System.out.println("CONFORMANCE: " + conformanceBinding.getValue().stringValue());
                        conformance = Boolean.valueOf(conformanceBinding.getValue().stringValue());
                    }
                    Binding group_binding = bs.getBinding("groups");
                    String groups_ = "";
                    if (group_binding != null) {
                        groups_ = group_binding.getValue().stringValue();
                    }
                    Set<String> contributors = new HashSet<String>(Arrays.asList(contribs.split(";")));
                    Set<String> groups = new HashSet<String>();
                    if (groups_ != null) {
                        groups.addAll(Arrays.asList(groups_.split(";")));
                    }
                    ModelMeta mm = id_model.get(model_curie);
                    if (mm == null) {
                        //look up model in in-memory cache to check edit state
                        boolean is_modified = m3.isModelModified(model_iri);
                        mm = new ModelMeta(model_curie, date, title, state, conformance, contributors, groups, is_modified);
                    }
                    //matching
                    for (String ind : ind_return.keySet()) {
                        String bindingName = ind.replace("?ind", "inds");
                        String[] ind_class_matches = bs.getBinding(bindingName).getValue().stringValue().split(" ", -1);
                        for (String ind_class_match : ind_class_matches) {
                            Set<String> matching_inds = mm.query_match.get(ind_return.get(ind));
                            if (matching_inds == null) {
                                matching_inds = new HashSet<String>();
                            }
                            matching_inds.add(ind_class_match);
                            mm.query_match.put(ind_return.get(ind), matching_inds);
                        }
                    }
                    id_model.put(model_curie, mm);
                }
            }
        } catch (QueryEvaluationException e) {
            r.message = "Query Evaluation Problem - probably a time out";
            r.error = e.getMessage();
            e.printStackTrace();
            return r;
        }
        if (n_count != null) {
            r.n = Integer.parseInt(n_count);
        } else {
            r.n = id_model.size();
            r.models = new LinkedHashSet<ModelMeta>(id_model.values());
        }
        try {
            result.close();
        } catch (QueryEvaluationException e) {
            r.message = "Query Evaluation Problem - can't close result set";
            r.error = e.getMessage();
            e.printStackTrace();
            return r;
        }
        //test
        //http://127.0.0.1:6800/modelsearch/?query=bla
        return r;
    }


    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public ModelSearchResult searchPostForm(
            @FormParam("taxon") Set<String> taxa,
            @FormParam("gp") Set<String> gene_product_class_uris,
            @FormParam("term") Set<String> terms,
            @FormParam("expand") String expand,
            @FormParam("pmid") Set<String> pmids,
            @FormParam("title") String title,
            @FormParam("state") Set<String> state,
            @FormParam("contributor") Set<String> contributor,
            @FormParam("group") Set<String> group,
            @FormParam("exactdate") String exactdate,
            @FormParam("date") String date,
            @FormParam("dateend") String datend,
            @FormParam("offset") int offset,
            @FormParam("limit") int limit,
            @FormParam("count") String count,
            @FormParam("debug") String debug,
            @FormParam("debug") Set<String> id) {
        ModelSearchResult result = new ModelSearchResult();
        result = search(taxa, gene_product_class_uris, terms, expand, pmids, title, state, contributor, group, exactdate, date, datend, offset, limit, count, debug, id);
        return result;
    }


}
