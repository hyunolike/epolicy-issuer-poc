package com.hyunolike.epolicy.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** epolicy.* 설정. 기본값은 "네트워크 없이 로컬에서 바로 돌아간다"를 기준으로 잡았다. */
@ConfigurationProperties(prefix = "epolicy")
public class EpolicyProperties {

    /** 증권에 표기되는 발행 주체. 합성 데이터 PoC 이므로 실존하지 않는 이름을 쓴다. */
    private String issuerName = "하이퍼라이크화재해상보험";

    /** 기본 양식. application.yml 의 값과 어긋나면 안 된다 — 테스트가 그 불일치를 잡는다. */
    private String defaultTemplateVersion = "v2";

    private final Pdf pdf = new Pdf();
    private final Sign sign = new Sign();
    private final Storage storage = new Storage();
    private final Batch batch = new Batch();

    public String getIssuerName() {
        return issuerName;
    }

    public void setIssuerName(String issuerName) {
        this.issuerName = issuerName;
    }

    public String getDefaultTemplateVersion() {
        return defaultTemplateVersion;
    }

    public void setDefaultTemplateVersion(String defaultTemplateVersion) {
        this.defaultTemplateVersion = defaultTemplateVersion;
    }

    public Pdf getPdf() {
        return pdf;
    }

    public Sign getSign() {
        return sign;
    }

    public Storage getStorage() {
        return storage;
    }

    public Batch getBatch() {
        return batch;
    }

    public static class Pdf {

        /** memory | temp-file. 배치 메모리 측정의 스위치다. */
        private String bufferStrategy = "memory";

        /** temp-file 전략일 때 스풀 디렉터리. */
        private String spoolDir = "build/spool";

        /**
         * XMP/문서정보의 CreatorTool 로 들어간다. 버전 문자열을 넣으면 빌드마다 바이트가 바뀌어
         * contentHash 멱등성이 깨지므로, 의도적으로 버전 없는 고정 문자열을 쓴다.
         */
        private String creatorTool = "epolicy-issuer";

        private String producer = "epolicy-issuer (openhtmltopdf + Apache PDFBox)";

        /** 폰트 서브셋 임베딩. PDF/A 는 전체 임베딩을 요구하지만 서브셋은 허용된다. */
        private boolean subsetFonts = true;

        public String getBufferStrategy() {
            return bufferStrategy;
        }

        public void setBufferStrategy(String bufferStrategy) {
            this.bufferStrategy = bufferStrategy;
        }

        public String getSpoolDir() {
            return spoolDir;
        }

        public void setSpoolDir(String spoolDir) {
            this.spoolDir = spoolDir;
        }

        public String getCreatorTool() {
            return creatorTool;
        }

        public void setCreatorTool(String creatorTool) {
            this.creatorTool = creatorTool;
        }

        public String getProducer() {
            return producer;
        }

        public void setProducer(String producer) {
            this.producer = producer;
        }

        public boolean isSubsetFonts() {
            return subsetFonts;
        }

        public void setSubsetFonts(boolean subsetFonts) {
            this.subsetFonts = subsetFonts;
        }
    }

    public static class Sign {

        private boolean enabled = true;

        /** PKCS#12 키스토어 경로. */
        private String keystorePath = "build/certs/test-signer.p12";

        private String keystorePassword = "changeit";

        private String keyAlias = "epolicy-test-signer";

        /**
         * 키스토어가 없으면 자체 서명 테스트 인증서를 그 자리에 만든다.
         *
         * <p>설계 문서는 {@code resources/certs/test-signer.p12} 를 레포에 두는 그림이었지만, 개인키를
         * 커밋하면 시크릿 스캐너가 걸고 "테스트용"이라는 맥락은 클론된 뒤 사라진다. 그래서 런타임에
         * 생성하는 쪽으로 바꿨다. 운영 프로파일에서는 false 로 두고 실제 키스토어를 주입한다.
         */
        private boolean generateIfAbsent = true;

        private String reason = "전자보험증권 교부";
        private String location = "Seoul, KR";
        private String contactInfo = "epolicy-issuer";

        private final Tsa tsa = new Tsa();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeystorePath() {
            return keystorePath;
        }

        public void setKeystorePath(String keystorePath) {
            this.keystorePath = keystorePath;
        }

        public String getKeystorePassword() {
            return keystorePassword;
        }

        public void setKeystorePassword(String keystorePassword) {
            this.keystorePassword = keystorePassword;
        }

        public String getKeyAlias() {
            return keyAlias;
        }

        public void setKeyAlias(String keyAlias) {
            this.keyAlias = keyAlias;
        }

        public boolean isGenerateIfAbsent() {
            return generateIfAbsent;
        }

        public void setGenerateIfAbsent(boolean generateIfAbsent) {
            this.generateIfAbsent = generateIfAbsent;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getContactInfo() {
            return contactInfo;
        }

        public void setContactInfo(String contactInfo) {
            this.contactInfo = contactInfo;
        }

        public Tsa getTsa() {
            return tsa;
        }
    }

    public static class Tsa {

        /**
         * 기본 false. 공개 TSA 는 외부 네트워크가 필요해서, 켜져 있으면 망 분리 환경이나 CI 에서
         * 발급이 통째로 멈춘다. B-T 를 만들 때만 명시적으로 켠다.
         */
        private boolean enabled = false;

        private String url = "https://freetsa.org/tsr";

        private int timeoutMillis = 5000;

        /** TSA 응답에 인증서를 포함시킬지. 장기검증(LTV)을 하려면 필요하다. */
        private boolean requestCertificate = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public boolean isRequestCertificate() {
            return requestCertificate;
        }

        public void setRequestCertificate(boolean requestCertificate) {
            this.requestCertificate = requestCertificate;
        }
    }

    public static class Storage {

        /** local | s3. PoC 에서는 local 만 구현한다. */
        private String type = "local";

        private String root = "build/documents";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    public static class Batch {

        private int chunkSize = 100;

        /** 1 이면 단일 스텝, 2 이상이면 contractNo 해시 기반 파티셔닝. */
        private int partitionCount = 1;

        /** 파티션 당 동시 실행 스레드 수. */
        private int gridConcurrency = 4;

        /** 건당 실패 허용 수. 초과하면 Job 을 세운다. */
        private int skipLimit = 100;

        /** 페이징 리더 fetch size. chunkSize 와 맞춰 두는 편이 커넥션 왕복이 적다. */
        private int pageSize = 100;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getPartitionCount() {
            return partitionCount;
        }

        public void setPartitionCount(int partitionCount) {
            this.partitionCount = partitionCount;
        }

        public int getGridConcurrency() {
            return gridConcurrency;
        }

        public void setGridConcurrency(int gridConcurrency) {
            this.gridConcurrency = gridConcurrency;
        }

        public int getSkipLimit() {
            return skipLimit;
        }

        public void setSkipLimit(int skipLimit) {
            this.skipLimit = skipLimit;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }
}
