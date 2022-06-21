package org.geneontology.minerva.server.handler;

import org.geneontology.minerva.ModelContainer;
import org.geneontology.minerva.MolecularModelManager;
import org.geneontology.minerva.MolecularModelManager.UnknownIdentifierException;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.UndoAwareMolecularModelManager.UndoMetadata;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.json.JsonAnnotation;
import org.geneontology.minerva.json.JsonTools;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.util.AnnotationShorthand;
import org.openrdf.repository.RepositoryException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Methods for creating a new model. This handles also all the default
 * annotations for models and provides methods to update date annotations
 */
abstract class ModelCreator {

    final UndoAwareMolecularModelManager m3;
    final CurieHandler curieHandler;
    private final String defaultModelState;
    private final Set<IRI> dataPropertyIRIs;

    static interface VariableResolver {
        public boolean notVariable(String id);

        public OWLNamedIndividual getVariableValue(String id) throws UnknownIdentifierException;

        static final VariableResolver EMPTY = new VariableResolver() {

            @Override
            public boolean notVariable(String id) {
                return true;
            }

            @Override
            public OWLNamedIndividual getVariableValue(String id) {
                return null;
            }
        };
    }

    ModelCreator(UndoAwareMolecularModelManager models, String defaultModelState) {
        this.m3 = models;
        this.curieHandler = models.getCuriHandler();
        this.defaultModelState = defaultModelState;
        Set<IRI> dataPropertyIRIs = new HashSet<IRI>();
        for (OWLDataProperty p : m3.getOntology().getDataPropertiesInSignature(Imports.INCLUDED)) {
            dataPropertyIRIs.add(p.getIRI());
        }
        this.dataPropertyIRIs = Collections.unmodifiableSet(dataPropertyIRIs);
    }

    ModelContainer createModel(String userId, Set<String> providerGroups, UndoMetadata token, VariableResolver resolver, JsonAnnotation[] annotationValues) throws UnknownIdentifierException, OWLOntologyCreationException {
        ModelContainer model = m3.generateBlankModel(token);
        Set<OWLAnnotation> annotations = extract(annotationValues, userId, providerGroups, resolver, model);
        annotations = addDefaultModelState(annotations, model.getOWLDataFactory());
        m3.addModelAnnotations(model, annotations, token);
        updateModelAnnotations(model, userId, providerGroups, token, m3);
        // Disallow undo of initial annotations
        m3.clearUndoHistory(model.getModelId());
        return model;
    }

    ModelContainer copyModel(IRI sourceModelId, String userId, Set<String> providerGroups, UndoMetadata token, VariableResolver resolver, JsonAnnotation[] annotationValues, Optional<String> maybeNewTitle) throws UnknownIdentifierException, OWLOntologyCreationException, RepositoryException, IOException, OWLOntologyStorageException {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        ModelContainer sourceModel = m3.getModel(sourceModelId);
        final String newTitle;
        if (maybeNewTitle.isPresent()) {
            newTitle = maybeNewTitle.get();
        } else {
            Optional<String> copyModelTitle = sourceModel.getAboxOntology().getAnnotations().stream()
                    .filter(ann -> ann.getProperty().getIRI().equals(AnnotationShorthand.title.getAnnotationProperty()))
                    .filter(ann -> ann.getValue().isLiteral())
                    .map(ann -> ann.getValue().asLiteral().get())
                    .map(OWLLiteral::getLiteral)
                    .map(title -> "Copy of " + title).min(String::compareTo);
            newTitle = copyModelTitle.orElse("Copy of " + sourceModelId.toString());
        }
        ModelContainer model = m3.generateBlankModel(token);
        final Set<OWLAnnotation> generatedAnnotations = createGeneratedAnnotations(model, userId, providerGroups);
        addDateAnnotation(generatedAnnotations, model.getOWLDataFactory());
        final Set<IRI> axiomEvidenceNodeIRIs = sourceModel.getAboxOntology().getAxioms().stream()
                .flatMap(ax -> ax.getAnnotations(df.getOWLAnnotationProperty(AnnotationShorthand.evidence.getAnnotationProperty())).stream())
                .filter(ann -> ann.getValue().isIRI())
                .map(ann -> ann.getValue().asIRI().get())
                .collect(Collectors.toSet());
        final Set<IRI> evidenceNodeIRIs = sourceModel.getAboxOntology().getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(aa -> aa.getProperty().getIRI().equals(AnnotationShorthand.evidence.getAnnotationProperty()))
                .filter(aa -> aa.getValue().isIRI())
                .map(aa -> aa.getValue().asIRI().get())
                .collect(Collectors.toSet());
        evidenceNodeIRIs.addAll(axiomEvidenceNodeIRIs);
        Map<OWLNamedIndividual, OWLNamedIndividual> oldToNew = m3.getIndividuals(sourceModelId).stream()
                .filter(i -> !evidenceNodeIRIs.contains(i.getIRI()))
                .collect(Collectors.toMap(sourceIndividual -> sourceIndividual, sourceIndividual -> m3.createIndividualNonReasoning(model, generatedAnnotations, token)));
        sourceModel.getAboxOntology().getAxioms(AxiomType.CLASS_ASSERTION, Imports.EXCLUDED).stream()
                .filter(ca -> !evidenceNodeIRIs.contains(ca.getIndividual().asOWLNamedIndividual().getIRI()))
                .forEach(ca -> m3.addType(model, oldToNew.get(ca.getIndividual().asOWLNamedIndividual()), ca.getClassExpression(), token));
        sourceModel.getAboxOntology().getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION, Imports.EXCLUDED).stream()
                .filter(opa -> !evidenceNodeIRIs.contains(opa.getSubject().asOWLNamedIndividual().getIRI()) && !evidenceNodeIRIs.contains(opa.getObject().asOWLNamedIndividual().getIRI()))
                .forEach(opa -> m3.addFact(model, opa.getProperty(), oldToNew.get(opa.getSubject().asOWLNamedIndividual()), oldToNew.get(opa.getObject().asOWLNamedIndividual()), generatedAnnotations, token));
        final Set<OWLAnnotation> annotationsWithModelState = addDefaultModelState(generatedAnnotations, model.getOWLDataFactory());
        annotationsWithModelState.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(AnnotationShorthand.wasDerivedFrom.getAnnotationProperty()), sourceModelId));
        annotationsWithModelState.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(AnnotationShorthand.title.getAnnotationProperty()), df.getOWLLiteral(newTitle)));
        m3.addModelAnnotations(model, annotationsWithModelState, token);
        updateModelAnnotations(model, userId, providerGroups, token, m3);
        // Disallow undo of initial annotations
        m3.clearUndoHistory(model.getModelId());
        m3.saveModel(model);
        return model;
    }

    boolean deleteModel(ModelContainer model) {
        if (model != null) {
            return m3.deleteModel(model);
        }
        return false;
    }

    private Set<OWLAnnotation> addDefaultModelState(Set<OWLAnnotation> existing, OWLDataFactory f) {
        IRI iri = AnnotationShorthand.modelstate.getAnnotationProperty();
        OWLAnnotationProperty property = f.getOWLAnnotationProperty(iri);
        OWLAnnotation ann = f.getOWLAnnotation(property, f.getOWLLiteral(defaultModelState));
        if (existing == null || existing.isEmpty()) {
            return Collections.singleton(ann);
        }
        existing.add(ann);
        return existing;
    }

    Set<OWLAnnotation> extract(JsonAnnotation[] values, String userId, Set<String> providerGroups, VariableResolver batchValues, ModelContainer model) throws UnknownIdentifierException {
        Set<OWLAnnotation> result = new HashSet<OWLAnnotation>();
        OWLDataFactory f = model.getOWLDataFactory();
        if (values != null) {
            for (JsonAnnotation jsonAnn : values) {
                if (jsonAnn.key != null && jsonAnn.value != null) {
                    AnnotationShorthand shorthand = AnnotationShorthand.getShorthand(jsonAnn.key, curieHandler);
                    if (shorthand != null) {
                        if (AnnotationShorthand.evidence == shorthand) {
                            IRI evidenceIRI;
                            if (batchValues.notVariable(jsonAnn.value)) {
                                evidenceIRI = curieHandler.getIRI(jsonAnn.value);
                            } else {
                                evidenceIRI = batchValues.getVariableValue(jsonAnn.value).getIRI();
                            }
                            result.add(create(f, shorthand, evidenceIRI));
                        } else {
                            result.add(create(f, shorthand, JsonTools.createAnnotationValue(jsonAnn, f)));
                        }
                    } else {
                        IRI pIRI = curieHandler.getIRI(jsonAnn.key);
                        if (dataPropertyIRIs.contains(pIRI) == false) {
                            OWLAnnotationValue annotationValue = JsonTools.createAnnotationValue(jsonAnn, f);
                            result.add(f.getOWLAnnotation(f.getOWLAnnotationProperty(pIRI), annotationValue));
                        }
                    }
                }
            }
        }
        addGeneratedAnnotations(userId, providerGroups, result, f);
        return result;
    }

    Map<OWLDataProperty, Set<OWLLiteral>> extractDataProperties(JsonAnnotation[] values, ModelContainer model) throws UnknownIdentifierException {
        Map<OWLDataProperty, Set<OWLLiteral>> result = new HashMap<OWLDataProperty, Set<OWLLiteral>>();

        if (values != null && values.length > 0) {
            OWLDataFactory f = model.getOWLDataFactory();
            for (JsonAnnotation jsonAnn : values) {
                if (jsonAnn.key != null && jsonAnn.value != null) {
                    AnnotationShorthand shorthand = AnnotationShorthand.getShorthand(jsonAnn.key, curieHandler);
                    if (shorthand == null) {
                        IRI pIRI = curieHandler.getIRI(jsonAnn.key);
                        if (dataPropertyIRIs.contains(pIRI)) {
                            OWLLiteral literal = JsonTools.createLiteral(jsonAnn, f);
                            if (literal != null) {
                                OWLDataProperty property = f.getOWLDataProperty(pIRI);
                                Set<OWLLiteral> literals = result.get(property);
                                if (literals == null) {
                                    literals = new HashSet<OWLLiteral>();
                                    result.put(property, literals);
                                }
                                literals.add(literal);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    void updateDate(ModelContainer model, OWLNamedIndividual individual, UndoMetadata token, UndoAwareMolecularModelManager m3) throws UnknownIdentifierException {
        final OWLDataFactory f = model.getOWLDataFactory();
        m3.updateAnnotation(model, individual, createDateAnnotation(f), token);
    }

    void updateModelAnnotations(ModelContainer model, String userId, Set<String> providerGroups, UndoMetadata token, MolecularModelManager<UndoMetadata> m3) throws UnknownIdentifierException {
        final OWLDataFactory f = model.getOWLDataFactory();
        if (userId != null) {
            Set<OWLAnnotation> annotations = new HashSet<OWLAnnotation>();
            annotations.add(create(f, AnnotationShorthand.contributor, userId));
            m3.addModelAnnotations(model, annotations, token);
        }
        for (String provider : providerGroups) {
            Set<OWLAnnotation> annotations = new HashSet<OWLAnnotation>();
            annotations.add(create(f, AnnotationShorthand.providedBy, provider));
            m3.addModelAnnotations(model, annotations, token);
        }
        m3.updateAnnotation(model, createDateAnnotation(f), token);
    }

    void addGeneratedAnnotations(String userId, Set<String> providerGroups, Set<OWLAnnotation> annotations, OWLDataFactory f) {
        if (userId != null) {
            annotations.add(create(f, AnnotationShorthand.contributor, userId));
        }
        for (String provider : providerGroups) {
            annotations.add(create(f, AnnotationShorthand.providedBy, provider));
        }
    }

    void addDateAnnotation(Set<OWLAnnotation> annotations, OWLDataFactory f) {
        annotations.add(createDateAnnotation(f));
    }

    OWLAnnotation createDateAnnotation(OWLDataFactory f) {
        return create(f, AnnotationShorthand.date, generateDateString());
    }

    /**
     * separate method, intended to be overridden during test.
     *
     * @return date string, never null
     */
    protected String generateDateString() {
        String dateString = MolecularModelJsonRenderer.AnnotationTypeDateFormat.get().format(new Date());
        return dateString;
    }

    Set<OWLAnnotation> createGeneratedAnnotations(ModelContainer model, String userId, Set<String> providerGroups) {
        Set<OWLAnnotation> annotations = new HashSet<OWLAnnotation>();
        OWLDataFactory f = model.getOWLDataFactory();
        addGeneratedAnnotations(userId, providerGroups, annotations, f);
        return annotations;
    }

    void updateDate(ModelContainer model, OWLObjectProperty predicate, OWLNamedIndividual subject, OWLNamedIndividual object, UndoMetadata token, UndoAwareMolecularModelManager m3) throws UnknownIdentifierException {
        final OWLDataFactory f = model.getOWLDataFactory();
        m3.updateAnnotation(model, predicate, subject, object, createDateAnnotation(f), token);
    }

    static OWLAnnotation create(OWLDataFactory f, AnnotationShorthand s, String literal) {
        return create(f, s, f.getOWLLiteral(literal));
    }

    static OWLAnnotation create(OWLDataFactory f, AnnotationShorthand s, OWLAnnotationValue v) {
        final OWLAnnotationProperty p = f.getOWLAnnotationProperty(s.getAnnotationProperty());
        return f.getOWLAnnotation(p, v);
    }
}
