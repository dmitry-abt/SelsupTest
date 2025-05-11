package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Клиент для работы с API CRPT с поддержкой ограничения запросов
 */
public class CrptApi {
    private static final String DEFAULT_API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final ContentType CONTENT_TYPE = ContentType.APPLICATION_JSON;

    private final String apiUrl;
    private final TimeUnit rateLimitTimeUnit;
    private final int requestLimit;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private Instant lastResetTime = Instant.now();
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Lock lock = new ReentrantLock();

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, HttpClients.createDefault(), DEFAULT_API_URL);
    }

    public CrptApi(TimeUnit timeUnit, int requestLimit, HttpClient httpClient, String apiUrl) {
        this.rateLimitTimeUnit = Objects.requireNonNull(timeUnit, "TimeUnit не может быть null");
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Лимит запросов должен быть положительным");
        }
        this.requestLimit = requestLimit;
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP клиент не может быть null");
        this.apiUrl = Objects.requireNonNull(apiUrl, "URL API не может быть null");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Создает документ в системе CRPT
     * @param document Документ для создания
     * @param signature Цифровая подпись
     * @return Ответ API в виде строки
     * @throws CrptApiException Ошибка при работе с API
     * @throws InterruptedException Прерывание ожидания
     */
    public String createDocument(Document document, String signature)
            throws CrptApiException, InterruptedException, ParseException {
        Objects.requireNonNull(document, "Документ не может быть null");
        Objects.requireNonNull(signature, "Подпись не может быть null");

        try {
            lock.lock();
            checkRateLimit();

            HttpPost request = createRequest(document, signature);
            return executeRequest(request);
        } finally {
            lock.unlock();
        }
    }

    private void checkRateLimit() throws InterruptedException {
        Instant now = Instant.now();
        long elapsedMillis = now.toEpochMilli() - lastResetTime.toEpochMilli();
        long timeUnitMillis = rateLimitTimeUnit.toMillis(1);

        if (elapsedMillis >= timeUnitMillis) {
            resetCounter(now);
            return;
        }

        while (requestCounter.get() >= requestLimit) {
            long remainingMillis = timeUnitMillis - elapsedMillis;
            Thread.sleep(remainingMillis);

            now = Instant.now();
            elapsedMillis = now.toEpochMilli() - lastResetTime.toEpochMilli();
            if (elapsedMillis >= timeUnitMillis) {
                resetCounter(now);
                break;
            }
        }
    }

    private void resetCounter(Instant resetTime) {
        requestCounter.set(0);
        lastResetTime = resetTime;
    }

    private HttpPost createRequest(Document document, String signature) throws CrptApiException {
        try {
            String json = objectMapper.writeValueAsString(document);
            HttpPost request = new HttpPost(apiUrl);
            request.setEntity(new StringEntity(json, CONTENT_TYPE));
            request.setHeader("Signature", signature);
            return request;
        } catch (IOException e) {
            throw new CrptApiException("Ошибка сериализации документа", e);
        }
    }

    private String executeRequest(HttpPost request) throws CrptApiException, ParseException {
        try (CloseableHttpResponse response = (CloseableHttpResponse) httpClient.execute(request)) {
            HttpEntity entity = response.getEntity();
            String responseBody = EntityUtils.toString(entity, CONTENT_TYPE.getCharset());
            requestCounter.incrementAndGet();
            return responseBody;
        } catch (IOException e) {
            throw new CrptApiException("Ошибка запроса к API", e);
        }
    }

    public static class CrptApiException extends Exception {
        public CrptApiException(String message) {
            super(message);
        }

        public CrptApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Документ для создания в системе CRPT
     */
    public static class Document {
        private Description description;
        @JsonProperty("doc_id")
        private String docId;
        @JsonProperty("doc_status")
        private String docStatus;
        @JsonProperty("doc_type")
        private String docType;
        @JsonProperty("importRequest")
        private boolean importRequest;
        @JsonProperty("owner_inn")
        private String ownerInn;
        @JsonProperty("participant_inn")
        private String participantInn;
        @JsonProperty("producer_inn")
        private String producerInn;
        @JsonProperty("production_date")
        private String productionDate;
        @JsonProperty("production_type")
        private String productionType;
        private List<Product> products;
        @JsonProperty("reg_date")
        private String regDate;
        @JsonProperty("reg_number")
        private String regNumber;

        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public String getDocId() {
            return docId;
        }

        public void setDocId(String docId) {
            this.docId = docId;
        }

        public String getDocStatus() {
            return docStatus;
        }

        public void setDocStatus(String docStatus) {
            this.docStatus = docStatus;
        }

        public String getDocType() {
            return docType;
        }

        public void setDocType(String docType) {
            this.docType = docType;
        }

        public boolean isImportRequest() {
            return importRequest;
        }

        public void setImportRequest(boolean importRequest) {
            this.importRequest = importRequest;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public void setOwnerInn(String ownerInn) {
            this.ownerInn = ownerInn;
        }

        public String getParticipantInn() {
            return participantInn;
        }

        public void setParticipantInn(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public void setProducerInn(String producerInn) {
            this.producerInn = producerInn;
        }

        public String getProductionDate() {
            return productionDate;
        }

        public void setProductionDate(String productionDate) {
            this.productionDate = productionDate;
        }

        public String getProductionType() {
            return productionType;
        }

        public void setProductionType(String productionType) {
            this.productionType = productionType;
        }

        public List<Product> getProducts() {
            return products;
        }

        public void setProducts(List<Product> products) {
            this.products = products;
        }

        public String getRegDate() {
            return regDate;
        }

        public void setRegDate(String regDate) {
            this.regDate = regDate;
        }

        public String getRegNumber() {
            return regNumber;
        }

        public void setRegNumber(String regNumber) {
            this.regNumber = regNumber;
        }
    }

    /**
     * Описание документа
     */
    public static class Description {
        @JsonProperty("participantInn")
        private String participantInn;

        public String getParticipantInn() {
            return participantInn;
        }

        public void setParticipantInn(String participantInn) {
            this.participantInn = participantInn;
        }
    }

    /**
     * Информация о продукте в документе
     */
    public static class Product {
        @JsonProperty("certificate_document")
        private String certificateDocument;
        @JsonProperty("certificate_document_date")
        private String certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        private String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        private String ownerInn;
        @JsonProperty("producer_inn")
        private String producerInn;
        @JsonProperty("production_date")
        private String productionDate;
        @JsonProperty("tnved_code")
        private String tnvedCode;
        @JsonProperty("uit_code")
        private String uitCode;
        @JsonProperty("uitu_code")
        private String uituCode;

        public String getCertificateDocument() {
            return certificateDocument;
        }

        public void setCertificateDocument(String certificateDocument) {
            this.certificateDocument = certificateDocument;
        }

        public String getCertificateDocumentDate() {
            return certificateDocumentDate;
        }

        public void setCertificateDocumentDate(String certificateDocumentDate) {
            this.certificateDocumentDate = certificateDocumentDate;
        }

        public String getCertificateDocumentNumber() {
            return certificateDocumentNumber;
        }

        public void setCertificateDocumentNumber(String certificateDocumentNumber) {
            this.certificateDocumentNumber = certificateDocumentNumber;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public void setOwnerInn(String ownerInn) {
            this.ownerInn = ownerInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public void setProducerInn(String producerInn) {
            this.producerInn = producerInn;
        }

        public String getProductionDate() {
            return productionDate;
        }

        public void setProductionDate(String productionDate) {
            this.productionDate = productionDate;
        }

        public String getTnvedCode() {
            return tnvedCode;
        }

        public void setTnvedCode(String tnvedCode) {
            this.tnvedCode = tnvedCode;
        }

        public String getUitCode() {
            return uitCode;
        }

        public void setUitCode(String uitCode) {
            this.uitCode = uitCode;
        }

        public String getUituCode() {
            return uituCode;
        }

        public void setUituCode(String uituCode) {
            this.uituCode = uituCode;
        }
    }

    public static void main(String[] args) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            CrptApi crptApi = new CrptApi(TimeUnit.MINUTES, 100, httpClient, DEFAULT_API_URL);

            Document doc = new Document();
            doc.setDescription(new Description());
            doc.setDocType("LP_INTRODUCE_GOODS");
            doc.setImportRequest(true);

            Product product = new Product();
            product.setCertificateDocument("certificate");
            doc.setProducts(List.of(product));

            String signature = "example_signature";
            String response = crptApi.createDocument(doc, signature);
            System.out.println("Ответ API: " + response);
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}