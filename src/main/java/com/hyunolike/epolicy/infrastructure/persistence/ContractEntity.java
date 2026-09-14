package com.hyunolike.epolicy.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 계약 원장(합성 데이터).
 *
 * <p>주민번호를 평문으로 둔다. 실 시스템이라면 컬럼 암호화 대상이지만, 이 PoC 는 <b>합성 데이터만</b>
 * 쓴다는 전제 위에 있고(README 스코프 아웃 참고) 암호화는 증명하려는 대상이 아니다. 대신 도메인으로
 * 올라오는 순간 {@code RegisteredNo} 로 감싸 원본 접근 경로를 한 군데로 좁힌다.
 */
@Entity
@Table(name = "contract")
public class ContractEntity {

    @Id
    @Column(name = "contract_no", length = 32, nullable = false)
    private String contractNo;

    @Column(name = "product_code", length = 32, nullable = false)
    private String productCode;

    @Column(name = "product_name", length = 128, nullable = false)
    private String productName;

    @Column(name = "policyholder_name", length = 64, nullable = false)
    private String policyholderName;

    @Column(name = "policyholder_rrn", length = 14, nullable = false)
    private String policyholderRrn;

    @Column(name = "policyholder_phone", length = 20)
    private String policyholderPhone;

    @Column(name = "policyholder_address", length = 200)
    private String policyholderAddress;

    @Column(name = "insured_name", length = 64, nullable = false)
    private String insuredName;

    @Column(name = "insured_rrn", length = 14, nullable = false)
    private String insuredRrn;

    @Column(name = "insured_phone", length = 20)
    private String insuredPhone;

    @Column(name = "insured_address", length = 200)
    private String insuredAddress;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "premium", nullable = false, precision = 15, scale = 0)
    private BigDecimal premium;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "contract_coverage",
            joinColumns = @JoinColumn(name = "contract_no"))
    @OrderColumn(name = "line_no")
    private List<CoverageRow> coverages = new ArrayList<>();

    protected ContractEntity() {
    }

    public ContractEntity(String contractNo, String productCode, String productName,
                          String policyholderName, String policyholderRrn, String policyholderPhone,
                          String policyholderAddress, String insuredName, String insuredRrn,
                          String insuredPhone, String insuredAddress, LocalDate periodStart,
                          LocalDate periodEnd, BigDecimal premium, LocalDate issuedOn,
                          List<CoverageRow> coverages) {
        this.contractNo = contractNo;
        this.productCode = productCode;
        this.productName = productName;
        this.policyholderName = policyholderName;
        this.policyholderRrn = policyholderRrn;
        this.policyholderPhone = policyholderPhone;
        this.policyholderAddress = policyholderAddress;
        this.insuredName = insuredName;
        this.insuredRrn = insuredRrn;
        this.insuredPhone = insuredPhone;
        this.insuredAddress = insuredAddress;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.premium = premium;
        this.issuedOn = issuedOn;
        this.coverages = coverages;
    }

    public String getContractNo() {
        return contractNo;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getProductName() {
        return productName;
    }

    public String getPolicyholderName() {
        return policyholderName;
    }

    public String getPolicyholderRrn() {
        return policyholderRrn;
    }

    public String getPolicyholderPhone() {
        return policyholderPhone;
    }

    public String getPolicyholderAddress() {
        return policyholderAddress;
    }

    public String getInsuredName() {
        return insuredName;
    }

    public String getInsuredRrn() {
        return insuredRrn;
    }

    public String getInsuredPhone() {
        return insuredPhone;
    }

    public String getInsuredAddress() {
        return insuredAddress;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BigDecimal getPremium() {
        return premium;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public List<CoverageRow> getCoverages() {
        return coverages;
    }

    /** 담보 한 줄. */
    @Embeddable
    public static class CoverageRow {

        @Column(name = "coverage_name", length = 128, nullable = false)
        private String name;

        @Column(name = "coverage_amount", nullable = false, precision = 15, scale = 0)
        private BigDecimal amount;

        @Column(name = "coverage_note", length = 256)
        private String note;

        protected CoverageRow() {
        }

        public CoverageRow(String name, BigDecimal amount, String note) {
            this.name = name;
            this.amount = amount;
            this.note = note;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getNote() {
            return note;
        }
    }
}
