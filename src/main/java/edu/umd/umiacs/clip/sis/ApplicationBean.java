package edu.umd.umiacs.clip.sis;

import ca.uwo.csd.ai.nlp.common.SparseVector;
import ca.uwo.csd.ai.nlp.kernel.CustomKernel;
import ca.uwo.csd.ai.nlp.kernel.KernelManager;
import ca.uwo.csd.ai.nlp.kernel.LinearKernel;
import ca.uwo.csd.ai.nlp.libsvm.ex.Instance;
import ca.uwo.csd.ai.nlp.libsvm.ex.SVMTrainer;
import ca.uwo.csd.ai.nlp.libsvm.svm_model;
import ca.uwo.csd.ai.nlp.libsvm.svm_node;
import ca.uwo.csd.ai.nlp.libsvm.svm_parameter;
import static edu.umd.umiacs.clip.sis.MessageConverter.BODY_TEXT;
import static edu.umd.umiacs.clip.sis.MessageConverter.MESSAGE_ID;
import static edu.umd.umiacs.clip.sis.MessageConverter.SUBJECT;
import edu.umd.umiacs.clip.tools.classifier.ConfusionMatrix;
import static edu.umd.umiacs.clip.tools.io.AllFiles.REMOVE_OLD_FILE;
import static edu.umd.umiacs.clip.tools.io.AllFiles.lines;
import static edu.umd.umiacs.clip.tools.io.AllFiles.readAllLines;
import static edu.umd.umiacs.clip.tools.io.AllFiles.write;
import edu.umd.umiacs.clip.tools.lang.LangUtils;
import edu.umd.umiacs.clip.tools.lang.LuceneUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import static java.lang.Math.min;
import static java.lang.Math.round;
import static java.lang.Math.sqrt;
import java.util.ArrayList;
import static java.util.Comparator.comparing;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.IntStream.range;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.faces.application.FacesMessage;
import static javax.faces.application.FacesMessage.SEVERITY_ERROR;
import static javax.faces.application.FacesMessage.SEVERITY_INFO;
import javax.faces.bean.ApplicationScoped;
import javax.faces.bean.ManagedBean;
import javax.faces.context.FacesContext;
import javax.mail.Session;
import net.fortuna.mstor.MStorMessage;
import net.fortuna.mstor.data.MboxFile;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.cxf.helpers.FileUtils;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import static edu.umd.umiacs.clip.sis.MessageConverter.ATTACHMENT_PARSED;

@ManagedBean(eager = true)
@ApplicationScoped
public class ApplicationBean {

    public String getThemeReplacement() {
        return null;
    }

    public String getTheme() {
        return theme;
    }

    public final void setThemeReplacement(String theme) {
        FacesMessage msg;
        if (new File(annotationsPath + theme + ".txt").exists()) {
            msg = new FacesMessage(SEVERITY_ERROR, "There is already a theme with the name \"" + theme + "\".", "");
        } else {
            this.theme = theme;
            annotations = new HashMap<>();
            model = null;
            kernel = new LinearKernel();
            predictions = null;
            vocab.clear();
            KernelManager.setCustomKernel(kernel);
            msg = new FacesMessage(SEVERITY_INFO, "You have successfully created the new theme \"" + theme + "\".", "");
        }
        FacesContext.getCurrentInstance().addMessage(null, msg);
    }

    /**
     * @return the annotations
     */
    public Map<String, Boolean> getAnnotations() {
        return annotations;
    }

    private transient IndexSearcher is;
    private Map<String, Boolean> annotations;
    private final List<Pair<String, List<Pair<String, String>>>> lexicons = new ArrayList<>();
    //private String rootPath = System.getenv().getOrDefault("SIS_PATH", System.getProperty("user.home") + "/SIS") + "/";
    private String rootPath = "/fs/clip-scratch/mossaab/sasc/enron/";
    private String indexPath = rootPath + "index";
    private String annotationsPath = rootPath + "annotations/";
    private String theme;
    private static final String VOCAB_NAME = "vocab.txt";
    private static final String MODEL_NAME = "model.svm";
    private static final String KERNEL_NAME = "kernel.svm";
    private static final String LEXICON_PATH = "edu/stanford/epadd/lexicons.zip";
    private static final String LEXICON_SUFFIX = "english.lex.txt";
    private float[] predictions;
    private boolean isTraining;
    private Map<Pair<String, String>, Integer> vocab = new HashMap<>();
    private svm_model model;
    private CustomKernel kernel;
    private String mboxPath = "";
    private boolean isIndexing;
    private int progress;

    public ApplicationBean() {
        try {
            File indexFile = new File(indexPath);
            if (indexFile.exists()) {
                Directory directory = FSDirectory.open(indexFile.toPath());
                if (DirectoryReader.indexExists(directory)) {
                    is = new IndexSearcher(DirectoryReader.open(directory));
                }
            }
            ZipInputStream zis = new ZipInputStream(getClass().getClassLoader().getResourceAsStream(LEXICON_PATH));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                lexicons.add(Pair.of(entry.getName().
                        replace(LEXICON_SUFFIX, "").replace(".", " ").trim(),
                        lines(zis).map(line -> line.split("#")[0].trim()).
                                filter(line -> !line.isEmpty()).
                                map(line -> line.split(":")).
                                map(pair -> Pair.of(pair[0], pair[1])).
                                collect(toList())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        new File(annotationsPath).mkdir();
        Optional<String> last = Stream.of(new File(annotationsPath).listFiles()).
                max(comparing(File::lastModified)).map(File::getName).
                map(name -> name.substring(0, name.length() - 4));
        if (last.isPresent()) {
            loadAnnotations(last.get());
        } else {
            setThemeReplacement("Default");
        }
    }

    public List<String> getThemes() {
        return Stream.of(new File(annotationsPath).listFiles()).
                map(File::getName).
                map(name -> name.substring(0, name.length() - 4)).
                sorted().collect(toList());
    }

    /**
     * @return the is
     */
    public IndexSearcher getIs() {
        return is;
    }

    public void saveAnnotations() {
        List<String> lines = annotations.entrySet().stream().
                map(entry -> (entry.getValue() ? "1" : "0") + "\t" + entry.getKey()).
                collect(toList());
        write(annotationsPath + theme + ".txt", lines, REMOVE_OLD_FILE);
    }

    /**
     * @return the lexicons
     */
    public List<Pair<String, List<Pair<String, String>>>> getLexicons() {
        return lexicons;
    }

    private static SparseVector getFeatures(Document doc, Map<Pair<String, String>, Integer> vocab, boolean isTraining) {
        SparseVector vector = new SparseVector();
        Stream.of(SUBJECT, BODY_TEXT, ATTACHMENT_PARSED).
                forEach(field -> {
                    String content = doc.get(field);
                    if (content != null && !content.isEmpty()) {
                        content = LuceneUtils.enStem(content);
                        if (!content.isEmpty()) {
                            LangUtils.toFreqMap(content).entrySet().forEach(entry -> {
                                Pair<String, String> key = Pair.of(field, entry.getKey());
                                Integer index = vocab.get(key);
                                if (index == null && isTraining) {
                                    index = vocab.size();
                                    vocab.put(key, index);
                                }
                                if (index != null) {
                                    vector.add(index, sqrt(entry.getValue()));
                                }
                            });
                        }
                    }
                });
        return vector;
    }

    public void predict() {
        predictions = new float[is.getIndexReader().numDocs()];
        try {
            for (int i = 0; i < predictions.length; i++) {
                predictions[i] = (float) svm_predict(model, new svm_node(getFeatures(is.doc(i), vocab, false)), kernel);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static double svm_predict(svm_model model, svm_node x, CustomKernel kernel) {
        return range(0, model.l).parallel().
                mapToDouble(i -> model.sv_coef[0][i] * kernel.evaluate(x, model.SV[i])).
                sum() - model.rho[0];
    }

    public float[] getPredictions() {
        if (predictions == null) {
            predict();
        }
        return predictions;
    }

    public void train() {
        isTraining = true;
        List<Instance> instances = new ArrayList<>();
        for (String id : annotations.keySet()) {
            TermQuery query = new TermQuery(new Term(MESSAGE_ID, id));
            try {
                Document doc = is.doc(is.search(query, 1).scoreDocs[0].doc);
                instances.add(new Instance(annotations.get(doc.get(MESSAGE_ID)) ? 1 : -1, getFeatures(doc, vocab, true)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        model = SVMTrainer.train(instances, new svm_parameter());
        isTraining = false;
    }

    public String getCrossValidation() {
        isTraining = true;
        progress = 0;
        Map<Pair<String, String>, Integer> vocabCV = new HashMap<>();
        List<Instance> instances = new ArrayList<>();
        for (String id : annotations.keySet()) {
            TermQuery query = new TermQuery(new Term(MESSAGE_ID, id));
            try {
                Document doc = is.doc(is.search(query, 1).scoreDocs[0].doc);
                instances.add(new Instance(annotations.get(doc.get(MESSAGE_ID)) ? 1 : -1, getFeatures(doc, vocabCV, true)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        svm_parameter params = new svm_parameter();
        List<Boolean> gold = new ArrayList<>();
        List<Boolean> preds = new ArrayList<>();
        int folds = min(10, instances.size());
        int i = 0;
        for (Pair<List<Instance>, List<Instance>> pair : split(instances, folds)) {
            svm_model modelVocab = SVMTrainer.train(pair.getLeft(), params);
            pair.getRight().stream().
                    map(instance -> instance.getLabel() > 0).forEach(gold::add);
            pair.getRight().stream().
                    map(instance -> svm_predict(modelVocab, new svm_node(instance.getData()), kernel) > 0).
                    forEach(preds::add);
            progress = (int) (100 * ++i / (folds));
        }
        ConfusionMatrix cm = new ConfusionMatrix(gold, preds);
        isTraining = false;
        return "Recall = " + format(cm.getRecall())
                + ", Precision = " + format(cm.getPrecision())
                + ", F1 = " + format(cm.getF1());
    }

    private static float format(float f) {
        return round(100 * f) / 100f;
    }

    private static List<Pair<List<Instance>, List<Instance>>> split(List<Instance> list, int folds) {
        List<Pair<List<Instance>, List<Instance>>> split = new ArrayList<>();
        for (int i = 0; i < folds; i++) {
            int testStart = Math.round(list.size() * i / (float) folds);
            int testEnd = Math.round(list.size() * (i + 1) / (float) folds);
            List<Instance> training = new ArrayList<>(list.subList(0, testStart));
            training.addAll(list.subList(testEnd, list.size()));
            split.add(Pair.of(training, list.subList(testStart, testEnd)));
        }
        return split;
    }

    public StreamedContent getFile() {
        byte[] vocabContent = vocab.entrySet().stream().
                sorted(comparing(Map.Entry::getValue)).map(Map.Entry::getKey).
                map(pair -> pair.getLeft() + "\t" + pair.getRight()).
                collect(joining("\n")).getBytes();
        ByteArrayOutputStream tarOS = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(tarOS)) {
            writeZip(zip, vocabContent, VOCAB_NAME);
            writeZip(zip, model, MODEL_NAME);
            writeZip(zip, KernelManager.getCustomKernel(), KERNEL_NAME);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new DefaultStreamedContent(new ByteArrayInputStream(tarOS.toByteArray()), "application/octet-stream", theme + ".zip");
    }

    public boolean isTrainingEnabled() {
        return !annotations.isEmpty() && !isTraining;
    }

    public void uploadModel(FileUploadEvent event) {
        predictions = null;
        FacesMessage msg = new FacesMessage(SEVERITY_ERROR, "Failed to process model", "");
        Map<Pair<String, String>, Integer> vocabTmp = new HashMap<>();
        svm_model modelTmp = null;
        CustomKernel kernelTmp = null;
        try (ZipInputStream zis = new ZipInputStream(event.getFile().getInputstream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(VOCAB_NAME)) {
                    lines(zis).map(line -> line.split("\t")).
                            forEach(pair -> vocabTmp.put(Pair.of(pair[0], pair[1]), vocabTmp.size() + 1));
                } else {
                    ObjectInputStream in = new ObjectInputStream(zis);
                    if (entry.getName().equals(MODEL_NAME)) {
                        modelTmp = (svm_model) in.readObject();
                    } else {
                        kernelTmp = (CustomKernel) in.readObject();
                    }
                }
            }
            if (!vocabTmp.isEmpty() && modelTmp != null && kernelTmp != null) {
                vocab.clear();
                vocab.putAll(vocabTmp);
                model = modelTmp;
                kernel = kernelTmp;
                msg = new FacesMessage(SEVERITY_INFO, "Model uploaded successfully", "");
            }
        } catch (IOException | ClassNotFoundException e) {
            msg = new FacesMessage(SEVERITY_ERROR, e.getMessage(), "");
            e.printStackTrace();
        }
        FacesContext.getCurrentInstance().addMessage(null, msg);
    }

    public boolean isPredictionEnabled() {
        return model != null;
    }

    private static void writeZip(ZipOutputStream zip, byte[] content, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void writeZip(ZipOutputStream zip, Object object, String name) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(os)) {
            oos.writeObject(object);
        } catch (IOException e) {
            e.printStackTrace();
        }
        writeZip(zip, os.toByteArray(), name);
    }

    /**
     * @return the mboxPath
     */
    public String getMboxPath() {
        return mboxPath;
    }

    public void indexMbox() {
        isIndexing = true;
        progress = 0;
        Properties props = new Properties();
        props.setProperty("mail.mime.address.strict", "false");
        Session session = Session.getInstance(props, null);
        FacesMessage msg;
        System.out.println(new Date() + " - Started indexing.");
        try {
            MboxFile file = new MboxFile(new File(mboxPath));
            int count = file.getMessageCount();
            if (is != null) {
                is.getIndexReader().close();
            }
            FileUtils.removeDir(new File(indexPath));
            try (IndexWriter iw = new IndexWriter(FSDirectory.open(new File(indexPath).toPath()), new IndexWriterConfig(new EnglishAnalyzer()))) {
                for (int i = 0; i < count; i++) {
                    iw.addDocument(new MessageConverter(new MStorMessage(session, file.getMessageAsStream(i))).toDocument());
                    if (i > 0 && i % 1000 == 0) {
                        System.out.println(new Date() + " - Messages indexed: " + (i + 1));
                        iw.commit();
                    }
                    progress = (100 * i) / count;
                }
                iw.commit();
                System.out.println(new Date() + " - Started merging.");
                iw.forceMerge(1);
                iw.commit();

                is = new IndexSearcher(DirectoryReader.open(FSDirectory.open(new File(indexPath).toPath())));

                progress = 100;
                msg = new FacesMessage(SEVERITY_INFO, "Mbox file indexed successfully", "");
            }
        } catch (IOException e) {
            e.printStackTrace();
            msg = new FacesMessage(SEVERITY_ERROR, e.getMessage(), "");
        }
        System.out.println(new Date() + " - Finished indexing.");
        FacesContext.getCurrentInstance().addMessage(null, msg);
        isIndexing = false;
    }

    public boolean isIndexingEnabled() {
        return !isIndexing;
    }

    /**
     * @param mboxPath the mboxPath to set
     */
    public void setMboxPath(String mboxPath) {
        this.mboxPath = mboxPath;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isIndexExists() {
        return is != null;
    }

    public final void loadAnnotations(String theme) {
        this.theme = theme;
        loadAnnotations();
    }

    public void newAnnotations() {
    }

    public void loadAnnotations() {
        annotations = readAllLines(annotationsPath + theme + ".txt").stream().
                map(line -> line.split("\t")).
                collect(toMap(pair -> pair[1], pair -> pair[0].equals("1")));
        model = null;
        kernel = new LinearKernel();
        predictions = null;
        vocab.clear();
        KernelManager.setCustomKernel(kernel);
    }
}
