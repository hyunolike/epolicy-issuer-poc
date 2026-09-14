# ICC 프로파일

`sRGB-v2-micro.icc` — [Compact ICC Profiles](https://github.com/saucecontrol/Compact-ICC-Profiles) 의
sRGB v2 프로파일. CC0(퍼블릭 도메인)이라 재배포에 제약이 없다.

PDF/A-1b 는 문서에 OutputIntent 하나를 요구하고, 그 안에 색 공간을 정의하는 ICC 프로파일이 들어가야
한다. "이 문서의 색은 이 프로파일 기준으로 해석하라"는 선언이고, 장기보존에서 색 재현을 보장하는 장치다.

456바이트짜리를 고른 이유는 크기다. 일반 sRGB 프로파일은 3~60KB 인데, 1만 건 발급이면 그대로 곱해진다.
이 프로파일은 필수 태그만 담아 같은 역할을 한다.

Apache PDFBox 3.0.7 에 번들된 ICC 는 `CGATS001Compat-v2-micro.icc`(CMYK) 뿐이라 RGB 문서에는 쓸 수 없다.
