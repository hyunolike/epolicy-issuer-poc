package com.hyunolike.epolicy.support;

import com.hyunolike.epolicy.infrastructure.persistence.ContractEntity;
import com.hyunolike.epolicy.infrastructure.persistence.ContractRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 합성 계약 데이터 생성기 (M1).
 *
 * <p><b>실 개인정보는 절대 쓰지 않는다.</b> 여기서 만드는 주민등록번호는 형식만 맞고 <b>검증번호가
 * 일부러 틀리도록</b> 만들어진다. 실제 주민번호는 마지막 자리가 앞 12자리의 가중합으로 정해지는데,
 * 이 생성기는 그 값이 아닌 다른 숫자를 넣는다. 따라서 어떤 조합도 실존 번호와 겹치지 않는다.
 * "랜덤이라 우연히 실제 번호가 나올 수도 있다"는 리스크를 구조적으로 없애는 장치다.
 *
 * <p>{@link Random} 에 고정 시드를 주는 것은 재현성 때문이다. 같은 시드로 만든 1만 건은 어느
 * 머신에서 돌려도 같은 데이터이고, 그래야 BENCHMARK.md 의 수치가 비교 가능한 값이 된다.
 */
public class SyntheticContractSeeder {

    private static final Logger log = LoggerFactory.getLogger(SyntheticContractSeeder.class);

    private static final long DEFAULT_SEED = 20260914L;
    private static final int INSERT_BATCH = 500;

    private static final String[] SURNAMES = {
            "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송", "류", "전"
    };
    private static final String[] GIVEN_NAMES = {
            "민준", "서연", "도윤", "지우", "예준", "하은", "시우", "서윤", "주원", "지호",
            "하준", "지민", "건우", "수아", "우진", "다은", "선우", "채원", "연우", "가은",
            "현우", "유진", "지훈", "소윤", "준서", "윤서", "동현", "예린", "성민", "나연"
    };
    private static final String[] CITIES = {
            "서울특별시 강남구 테헤란로 152", "부산광역시 해운대구 센텀중앙로 79",
            "인천광역시 연수구 컨벤시아대로 165", "대구광역시 동구 동대구로 489",
            "대전광역시 유성구 대학로 99", "광주광역시 서구 상무중앙로 61",
            "경기도 성남시 분당구 판교역로 235", "경기도 수원시 영통구 광교중앙로 145",
            "강원특별자치도 춘천시 중앙로 1", "제주특별자치도 제주시 첨단로 242"
    };

    private record Product(String code, String name) {
    }

    private static final Product[] PRODUCTS = {
            new Product("PA0101", "무배당 일반상해보험"),
            new Product("PA0202", "무배당 운전자보험"),
            new Product("PA0303", "무배당 여행자보험"),
            new Product("PA0404", "무배당 실손의료비보험"),
            new Product("PA0505", "무배당 질병보장보험")
    };

    private record CoverageTemplate(String name, long minAmount, long maxAmount, String note) {
    }

    private static final CoverageTemplate[] COVERAGES = {
            new CoverageTemplate("상해사망", 50_000_000L, 300_000_000L, "보험기간 중 상해의 직접 결과로 사망한 경우"),
            new CoverageTemplate("상해후유장해", 30_000_000L, 200_000_000L, "장해지급률에 따라 비례 지급"),
            new CoverageTemplate("질병사망", 20_000_000L, 150_000_000L, "보험기간 중 질병으로 사망한 경우"),
            new CoverageTemplate("입원일당", 30_000L, 100_000L, "1일 이상 입원 시 1일당 지급 (180일 한도)"),
            new CoverageTemplate("골절진단", 500_000L, 3_000_000L, "골절로 진단 확정 시 1회 지급"),
            new CoverageTemplate("배상책임", 10_000_000L, 100_000_000L, "일상생활 중 타인에게 입힌 손해 (자기부담금 있음)")
    };

    private final ContractRepository repository;

    public SyntheticContractSeeder(ContractRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int seed(int count) {
        return seed(count, DEFAULT_SEED);
    }

    @Transactional
    public int seed(int count, long randomSeed) {
        Random random = new Random(randomSeed);
        List<ContractEntity> buffer = new ArrayList<>(INSERT_BATCH);
        int inserted = 0;
        for (int i = 1; i <= count; i++) {
            buffer.add(generate(i, random));
            if (buffer.size() >= INSERT_BATCH) {
                repository.saveAll(buffer);
                // saveAll 만으로는 영속성 컨텍스트가 계속 자란다. 1만 건이면 여기서 힙이 먼저 터진다.
                repository.flush();
                inserted += buffer.size();
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            repository.saveAll(buffer);
            repository.flush();
            inserted += buffer.size();
        }
        log.info("합성 계약 {}건을 생성했습니다 (seed={})", inserted, randomSeed);
        return inserted;
    }

    private ContractEntity generate(int index, Random random) {
        Product product = PRODUCTS[random.nextInt(PRODUCTS.length)];
        String contractNo = "KB-2026-%04d-%04d".formatted((index - 1) / 10_000 + 1, (index - 1) % 10_000);

        String holderName = name(random);
        String holderRrn = syntheticRrn(random);
        // 계약자와 피보험자가 같은 계약이 현실에서 다수다. 70% 비율로 맞춘다.
        boolean selfContract = random.nextInt(100) < 70;
        String insuredName = selfContract ? holderName : name(random);
        String insuredRrn = selfContract ? holderRrn : syntheticRrn(random);

        LocalDate issuedOn = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(240));
        LocalDate start = issuedOn.plusDays(1);
        LocalDate end = start.plusYears(1 + random.nextInt(3));

        return new ContractEntity(
                contractNo, product.code(), product.name(),
                holderName, holderRrn, phone(random), CITIES[random.nextInt(CITIES.length)],
                insuredName, insuredRrn, phone(random), CITIES[random.nextInt(CITIES.length)],
                start, end, BigDecimal.valueOf(30_000L + random.nextInt(40) * 5_000L),
                issuedOn, coverages(random));
    }

    private List<ContractEntity.CoverageRow> coverages(Random random) {
        int count = 2 + random.nextInt(4);
        List<ContractEntity.CoverageRow> rows = new ArrayList<>(count);
        List<CoverageTemplate> pool = new ArrayList<>(List.of(COVERAGES));
        for (int i = 0; i < count; i++) {
            CoverageTemplate template = pool.remove(random.nextInt(pool.size()));
            long span = template.maxAmount() - template.minAmount();
            long amount = template.minAmount() + Math.round(random.nextDouble() * span / 10_000d) * 10_000L;
            rows.add(new ContractEntity.CoverageRow(
                    template.name(), BigDecimal.valueOf(amount), template.note()));
        }
        return rows;
    }

    private String name(Random random) {
        return SURNAMES[random.nextInt(SURNAMES.length)] + GIVEN_NAMES[random.nextInt(GIVEN_NAMES.length)];
    }

    private String phone(Random random) {
        return "010-%04d-%04d".formatted(random.nextInt(10_000), random.nextInt(10_000));
    }

    /**
     * 형식만 맞고 검증번호는 반드시 틀린 주민등록번호를 만든다.
     *
     * <p>실제 주민번호의 13번째 자리는 앞 12자리에 가중치 2,3,4,5,6,7,8,9,2,3,4,5 를 곱해 더한 뒤
     * 11로 나눈 나머지를 11에서 뺀 값(mod 10)이다. 여기서는 그 값에 1을 더해 <b>항상 어긋나게</b> 한다.
     * 우연히 실존 번호가 생성될 가능성을 0으로 만드는 것이 목적이다.
     */
    private String syntheticRrn(Random random) {
        int year = 60 + random.nextInt(45);
        int month = 1 + random.nextInt(12);
        int day = 1 + random.nextInt(28);
        char genderDigit = (char) ('1' + random.nextInt(2) + (year >= 100 ? 2 : 0));
        String birth = "%02d%02d%02d".formatted(year % 100, month, day);
        String middle = "%05d".formatted(random.nextInt(100_000));
        String first12 = birth + genderDigit + middle;

        int[] weights = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (first12.charAt(i) - '0') * weights[i];
        }
        int correct = (11 - (sum % 11)) % 10;
        int deliberatelyWrong = (correct + 1) % 10;
        return birth + "-" + genderDigit + middle + deliberatelyWrong;
    }
}
