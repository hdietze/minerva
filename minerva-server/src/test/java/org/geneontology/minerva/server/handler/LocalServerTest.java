package org.geneontology.minerva.server.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Server;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.curie.CurieMappings;
import org.geneontology.minerva.curie.DefaultCurieHandler;
import org.geneontology.minerva.curie.MappedCurieHandler;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.server.StartUpTool;
import org.geneontology.minerva.server.StartUpTool.MinervaStartUpConfig;
import org.geneontology.minerva.server.handler.M3BatchHandler.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import owltools.io.ParserWrapper;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LocalServerTest {

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    private static CurieHandler curieHandler = null;
    private static UndoAwareMolecularModelManager models = null;
    private static Server server = null;
    private static String urlPrefix;
    static final String go_lego_journal_file = "/tmp/test-go-lego-blazegraph.jnl";


    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        init(new ParserWrapper());
    }

    @AfterClass
    public static void afterClass() throws Exception {
        models.dispose();
        server.stop();
        server.destroy();
    }

    @After
    public void after() {
    }

    static void init(ParserWrapper pw) throws Exception {
        final OWLOntology tbox = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(new File("src/test/resources/go-lego-minimal.owl")));
        // curie handler
        final String modelIdcurie = "gomodel";
        final String modelIdPrefix = "http://model.geneontology.org/";
        final CurieMappings localMappings = new CurieMappings.SimpleCurieMappings(Collections.singletonMap(modelIdcurie, modelIdPrefix));
        curieHandler = new MappedCurieHandler(DefaultCurieHandler.loadDefaultMappings(), localMappings);
        models = new UndoAwareMolecularModelManager(tbox, curieHandler, modelIdPrefix, folder.newFile().getAbsolutePath(), null, go_lego_journal_file, true);

        MinervaStartUpConfig conf = new MinervaStartUpConfig();
        conf.reasonerOpt = "elk";
        conf.useRequestLogging = true;
        conf.checkLiteralIds = false;
        conf.lookupService = null;
        conf.importantRelations = null;
        conf.port = 6800;
        conf.contextString = "/";
        server = StartUpTool.startUp(models, conf, null);
        urlPrefix = "http://localhost:" + conf.port + conf.contextString;
    }

    @Test
    public void testLongGet() throws Exception {
        String longGetSuffix = FileUtils.readFileToString(new File("src/test/resources/server-test/long-get.txt"));
        String urlString = urlPrefix + longGetSuffix;
        URL url = new URL(urlString);
        String responseString = IOUtils.toString(url.openStream());
        M3BatchResponse response = parseResponse(responseString);
        assertEquals(response.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response.messageType);
    }

    @Test
    public void testPost() throws Exception {
        String urlString = urlPrefix + "m3Batch";
        final URL url = new URL(urlString);

        final Map<String, String> params = new LinkedHashMap<>();
        params.put("uid", "uid-1");
        params.put("intention", "query");
        params.put("requests", createMetaGetRequest());

        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (postData.length() != 0) postData.append('&');
            postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(param.getValue(), "UTF-8"));
        }
        byte[] postDataBytes = postData.toString().getBytes("UTF-8");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
        conn.setDoOutput(true);
        conn.getOutputStream().write(postDataBytes);

        String responseString = IOUtils.toString(conn.getInputStream(), "UTF-8");
        M3BatchResponse response = parseResponse(responseString);
        assertEquals(response.message, M3BatchResponse.MESSAGE_TYPE_SUCCESS, response.messageType);

    }

    private M3BatchResponse parseResponse(String responseString) {
        Gson gson = new GsonBuilder().create();
        M3BatchResponse response = gson.fromJson(responseString, M3BatchResponse.class);
        return response;
    }

    private String createMetaGetRequest() {
        M3Request r = new M3Request();
        r.entity = Entity.meta;
        r.operation = Operation.get;
        r.arguments = new M3Argument();
        String json = MolecularModelJsonRenderer.renderToJson(new M3Request[]{r}, false);
        return json;

    }
}
