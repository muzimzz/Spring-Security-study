# 🛡️ Spring Security A to Z

> **Note**: 이 문서는 서블릿 기반 애플리케이션의 **URL 단위 인증/인가 필터 체인**을 처음부터 끝까지 순서대로 다룬다. `@PreAuthorize`/`@Secured` 같은 메서드 시큐리티는 완전히 다른 축(AOP 기반)이라 이 문서 범위 밖이다.
> 부록(맨 아래)은 "세션 기반이냐 JWT냐", "토큰을 헤더로 주고받냐 쿠키로 주고받냐"에 따라 같은 개념이 어떻게 달라지는지를 모았다 — 본문(0~8장)은 그 차이와 무관하게 항상 성립하는 공통 흐름만 다룬다.

---

## 0. 전체 흐름 한눈에 (A to Z)

요청 하나가 들어와서 컨트롤러에 도달하고 응답이 나가기까지, Security 관점에서 벌어지는 일을 한 문장씩으로 압축하면 이렇다.

1. 서블릿 컨테이너가 요청을 받으면, `DelegatingFilterProxy`가 그걸 스프링이 관리하는 `FilterChainProxy`로 넘긴다.
2. `FilterChainProxy`는 등록된 여러 `SecurityFilterChain` 중 이 요청의 URL에 매칭되는 하나를 고른다.
3. 그 체인에 등록된 필터들을 순서대로 통과한다 — 이 필터들이 하는 일은 크게 세 가지뿐이다: **(a) 이전에 이미 인증된 상태가 있으면 복원**, **(b) 지금 이 요청이 로그인 시도라면 인증을 수행**, **(c) 마지막에 "이 사람이 이 리소스에 접근해도 되는가"를 판정**.
4. 인증도 안 됐고 이 요청도 로그인 시도가 아니라면, 필터들은 `Authentication`을 "익명 사용자"로 채워 넣고 그냥 통과시킨다 — `SecurityContextHolder`는 절대 완전히 비어있지 않다.
5. 맨 끝 `AuthorizationFilter`가 URL 규칙(`authorizeHttpRequests()`)에 따라 통과/차단을 결정한다. 차단되면 인증 여부에 따라 401 또는 403으로 갈린다.
6. 통과하면 그제서야 `DispatcherServlet`으로 넘어가 컨트롤러가 실행된다.
7. 응답이 나간 뒤, 요청 스레드에 붙어있던 `SecurityContext`(ThreadLocal)를 정리한다.

아래 1~8장은 이 7단계를 각각 자세히 뜯어본 것이다.

---

## 1. 진입점 — 서블릿 필터 하나가 스프링 빈들의 체인으로 위임되는 구조

Spring Security는 서블릿 스펙의 `Filter` 인터페이스 위에서 동작하지만, 실제 로직은 전부 스프링이 관리하는 빈으로 구현돼 있다. 이 둘을 잇는 게 다음 두 클래스다.

- **`DelegatingFilterProxy`**: 서블릿 컨테이너가 아는 유일한 `Filter`. 하는 일은 요청을 스프링 컨테이너의 `springSecurityFilterChain`이라는 이름의 빈으로 그대로 위임하는 것뿐이다.
- **`FilterChainProxy`**: 그 빈의 실체. 내부에 하나 이상의 `SecurityFilterChain`을 들고 있다가, 요청 URL에 매칭되는 체인을 골라 그 안의 필터들을 순서대로 실행한다.

`@Bean SecurityFilterChain filterChain(HttpSecurity http)`를 여러 개 정의하면(예: `/api/**`용 하나, 나머지용 하나) `FilterChainProxy`가 `@Order`와 `securityMatcher()`를 보고 요청마다 그중 하나만 골라 태운다. 지금 이 문서에서 다루는 "필터 체인"은 그렇게 선택된 **하나의 `SecurityFilterChain` 내부**의 순서다.

---

## 2. 거시적 필터 체인 흐름

```
[클라이언트 요청]
       │
       ▼
 1. WebAsyncManagerIntegrationFilter (비동기 처리 시 SecurityContext를 작업 스레드로 전파 준비) (스프링 자동 등록)
       │
       ▼
 2. SecurityContextHolderFilter (이전 요청에서 저장된 SecurityContext 복원 — 세션 등) 
       │
       ▼
 3. HeaderWriterFilter (보안 응답 헤더 추가), CorsFilter (CORS 검증·Preflight 처리)
       │
       ▼
 4. CsrfFilter (CSRF 토큰 검증)
       │
       ▼
 5. LogoutFilter (로그아웃 요청 여부 확인 및 처리) (jwt의 경우 토큰 폐기를 위한 구현 필요)
       │
       ▼
 6. UsernamePasswordAuthenticationFilter (인증 방식에 따라 커스텀 구현)
       ├── [로그인 요청이 아니면] ➔ 그냥 통과 (다음 필터로)
       ├── [성공시] ➔ AuthenticationSuccessHandler (리다이렉트 or JWT/JSON 반환)
       └── [실패시] ➔ AuthenticationFailureHandler (로그인페이지 이동 or 401 JSON)
       │
       ▼
 7. ConcurrentSessionFilter (동시 접속 / 중복 로그인 제어) (stateless에서는 등록 안함)
       │
       ▼
 8. AnonymousAuthenticationFilter (여기까지도 인증이 안 됐다면 "익명 사용자" Authentication을 채워 넣음
                                    — 이 덕분에 SecurityContextHolder는 이후 필터 입장에서 절대 null이 아니다)
       │
       ▼
 9. SessionManagementFilter (세션 고정 공격 방지, 세션 정책 적용)
       │
       ▼
10. ExceptionTranslationFilter (뒤이어 발생할 인증/인가 예외를 감지하려고 대기하며 다음 필터를 try-catch로 감쌈)
       │
       ▼
11. AuthorizationFilter (인가/권한 검사 — authorizeHttpRequests()의 requestMatchers 규칙 적용)
       (필터 자체는 건드리지 않지만 AuthenticationEntryPoint, AccessDeniedHandler 구현)
       ├── [미인증 사용자] ➔ (10번이 잡아서) AuthenticationEntryPoint 실행 → 401 Unauthorized
       └── [인증은 됐으나 권한 부족] ➔ (10번이 잡아서) AccessDeniedHandler 실행 → 403 Forbidden
       │
       ▼ (모든 시큐리티 필터 통과)
12. DispatcherServlet (스프링 MVC 진입)
       │
13. HandlerMapping & HandlerAdapter (컨트롤러 탐색 및 실행)
       │
14. Controller / RestController (비즈니스 로직 실행 및 응답 생성)
       │
15. Response Commit & SecurityContext Clean-up (ThreadLocal 정돈)
```

**정리 포인트**

- **2번(`SecurityContextHolderFilter`)이 6번(인증 필터)보다 앞에 있다.** 이게 핵심이다 — 로그인은 최초 1회만 6번을 실제로 타고, 그 이후 요청들은 2번이 이전에 저장해둔 `SecurityContext`를 복원해주기 때문에 6번을 그냥 통과만 하면서도 "이미 로그인된 사용자"로 처리된다. (다만 이 필터가 실제로 "복원할 게 있는지"는 인증 방식에 따라 완전히 달라진다 — 부록 A-1 참고.)
- **`RequestMatcher`는 독립된 필터가 아니다.** URL/HTTP 메서드를 매칭하는 로직은 `CsrfFilter`·`UsernamePasswordAuthenticationFilter`(`requiresAuthentication()`)·`AuthorizationFilter` 등 여러 필터가 "내가 이 요청을 처리해야 하나?"를 판단할 때 내부적으로 쓰는 유틸리티다. 체인의 한 단계로 그리면 오해하기 쉬워 이 다이어그램에는 별도 번호를 주지 않았다.
- **8번(`AnonymousAuthenticationFilter`)** 덕분에 11번의 `AuthorizationFilter`는 "인증됐는가"를 판단할 때 `Authentication == null`을 걱정할 필요가 없다 — 항상 뭔가(진짜 로그인 사용자 or 익명 사용자) 들어있다.
- **10번과 11번의 관계**: 401/403을 실제로 갈라 처리하는 건 11번(`AuthorizationFilter`)이 아니라 10번(`ExceptionTranslationFilter`)이다. 11번은 그냥 `AccessDeniedException`을 던질 뿐이고, 그걸 감싸고 있던 10번이 예외를 잡아서 "지금 Authentication이 익명이면 401, 진짜 사용자인데 권한만 부족하면 403"으로 나눠 처리한다.
- **1번(`WebAsyncManagerIntegrationFilter`)**은 컨트롤러가 `Callable`/`DeferredResult`/`SseEmitter` 같은 비동기 방식으로 응답할 때를 대비한 준비 단계다. 구체적으로 어떤 문제를 막아주는지는 부록 A-3에서 실제 사례로 다룬다.

---

## 3. 미시적 흐름 — ID/PW 기반 인증의 7단계

클라이언트가 `/login`에 아이디·비밀번호를 POST했을 때, 위 6번 필터 내부에서 벌어지는 일이다.

```
[클라이언트 요청]
       │
       ▼
 1. UsernamePasswordAuthenticationFilter (요청 낚아채기 & 미인증 토큰 생성)
       │
       ▼
 2. AuthenticationManager (ProviderManager) (인증 위임, 스프링 자동 구성)
       │
       ▼
 3. DaoAuthenticationProvider (실제 인증 로직 수행)
       │ ── 4. UserDetailsService.loadUserByUsername() (DB 사용자 조회)
       │      (CustomUserDetailService 구현)
       │ ── 5. PasswordEncoder.matches() (비밀번호 검증)
       ▼
 6. Authentication (인증 완료된 토큰 생성)
       │
       ▼
 7. SecurityContextHolder (SecurityContext에 인증 완료 객체 저장)
       │
       ▼
[DispatcherServlet으로 이동 (컨트롤러 실행)]
```

**단계별 역할**

- **`UsernamePasswordAuthenticationFilter`**: HTTP 요청에서 username·password를 꺼내 아직 검증되지 않은 `UsernamePasswordAuthenticationToken`을 만든다.
- **`AuthenticationManager`(`ProviderManager`)**: 인증을 총괄하며, 등록된 `AuthenticationProvider` 목록 중 이 토큰 타입을 지원하는(`supports()`) 것에 위임한다.
- **`DaoAuthenticationProvider`**: DB 기반 아이디/비밀번호 인증을 전담.
- **`UserDetailsService.loadUserByUsername()`**: DB에서 사용자 조회 → `UserDetails`로 반환.
- **`PasswordEncoder.matches()`**: 입력 평문 비밀번호와 DB의 암호화된 비밀번호를 비교.
- **`Authentication` 객체 생성**: 검증 성공 시 권한(`Authorities`) 정보가 포함된, 인증 완료 상태의 새 토큰을 만든다. (실무적으로는 이 생성이 `DaoAuthenticationProvider` 내부에서 바로 일어나지만, 흐름 이해를 위해 별도 단계로 표기했다.)
- **`SecurityContextHolder`**: 인증 완료 객체를 `SecurityContext`에 보관해, 이 요청 스레드(ThreadLocal) 어디서든 로그인 유저 정보를 꺼낼 수 있게 한다.

---

### CustomUserDetails
```
1. 일반 ID/PW 로그인
public class CustomUserDetails implements UserDetails {
    private final Member member;   // DB에서 방금 조회해온 회원 엔티티를 그대로 감쌈

    @Override
    public String getUsername() {
        return member.getUsername();   // 실제 로그인 아이디
    }

    @Override
    public String getPassword() {
        return member.getPassword();   // DB에 암호화 저장된 비밀번호 해시 — PasswordEncoder.matches()가 이 값을 씀
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return member.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority(r.name()))
                .toList();
    }

    @Override
    public boolean isEnabled() { return member.isEnabled(); }        // 탈퇴/정지 계정 여부를 실제로 반영
    @Override
    public boolean isAccountNonLocked() { return !member.isLocked(); }
    // ...
}
```
```
2. jwt
public class CustomUserDetails implements UserDetails {
    private final UUID memberId;
    private final MemberRole role;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    // jwt는 비밀번호가 없음
    @Override
    public @Nullable String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return String.valueOf(memberId);   // 로그인 아이디가 아니라 그냥 memberId
    }

    // jwt의 접근 금지는 토큰으로 관리함. isEnabled(), isAccountNonLocked() 필요x
```

###  jwt 인증 방식  
```
.formLogin(login -> login.disable())   // ① UsernamePasswordAuthenticationFilter 자체를 체인에 아예 안 만듦

.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
// ② "UsernamePasswordAuthenticationFilter가 있었을 그 위치"에 JwtAuthenticationFilter를 꽂음
```

JwtAuthenticationFilter.doFilterInternal()를 구현해 매 요청마다 헤더/쿠키의 토큰을 검증한다.  
#3 의 7단계가 없이 토큰을 꺼내 서명과 만료 검증을 거친 후 클레임을 파싱하여  
이를 토대로 Authentication를 직접 조립한 후 SecurityContextHolder에 저장한다.  

```
String token = resolveToken(request);          // ① Authorization 헤더 우선, 없으면 accessToken 쿠키

if (token == null) {
    filterChain.doFilter(request, response);   // ② 토큰 자체가 없으면 그냥 통과 (인증 안 된 채로)
    return;    // 로그인 안 한 사용자와 같은 원리: AuthorizationFilter에서 필터링 됨 
}

if (jwtTokenProvider.validateToken(token)) {    // ③ 서명·만료 검증
    UUID memberId = jwtTokenProvider.getMemberId(token);   // ④ 클레임에서 파싱
    MemberRole role = jwtTokenProvider.getRole(token);
    // 서명으로 토큰 위변조가 불가능하다고 신뢰하여 DB를 건드리지 않고 클레임만 꺼내서 사용 → O(1)

    CustomUserDetails userDetails = new CustomUserDetails(memberId, role);
    UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    /**
     * jwt지만 그대로 UsernamePasswordAuthenticationToken 사용.
     * 원래 id/pw 로그인 할 때 커스텀 JwtAuthenticationFilter 대신 스프링 내부에서
     * UsernamePasswordAuthenticationFilter를 거치는 로그인 과정에서는
     * 미인증토큰 생성 + loadUserByUsername() / passwordEncoder.matches() 검증 + 인증완료토큰 생성
     * 3단계 과정이 일어나지만, 위에서 구현한 JwtAuthenticationFilter에서는 인증완료토큰(authentication)만 만든다.
     * UsernamePasswordAuthenticationToken의 파라미터가 2개(principal, credentials)면 미인증토큰,
     * 3개((principal, credentials, authorities)면 인증완료토큰으로 인식함
     */

    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    // 세션 사용 안하므로 해당 요청 스레드에서만 인증 처리 완료됨. 요청마다 매번 동일 과정 반복
}
// 검증 실패해도 예외 안 던지고 그냥 아래로 흐름 — SecurityContextHolder는 그냥 비어있는 채로 다음 필터로 감

filterChain.doFilter(request, response);
```


## 4. 인증 필터의 감싸는 구조 (Wrapper)

6번 필터(`AbstractAuthenticationProcessingFilter`)는 직접 DB를 조회하거나 비밀번호를 비교하지 않는다. 내부의 Manager·Provider에 위임하고, 전체 과정을 `try-catch`로 크게 감싸 성공/실패를 갈라 처리하는 바깥 상자 역할만 한다.

```java
// AbstractAuthenticationProcessingFilter 의 doFilter() 내부 핵심 동작 구조
public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
    try {
        // [1] 위임: AuthenticationManager -> Provider -> UserDetailsService/PasswordEncoder 진행
        // (내부에서 DB 조회 및 비밀번호 검증)
        Authentication authResult = attemptAuthentication(request, response);
        if (authResult == null) return;

        // [2] 성공 처리: try 블록 내부 맨 마지막 실행
        // (SecurityContext에 저장 + AuthenticationSuccessHandler 실행!)
        successfulAuthentication(request, response, chain, authResult);
    } catch (AuthenticationException failed) {

        // [3] 실패 처리: 도중에 어디서든 예외(Exception)가 터지면 catch로 수집!
        // (SecurityContext 비우기 + AuthenticationFailureHandler 실행!)
        unsuccessfulAuthentication(request, response, failed);
    }
}
```

---

## 5. 인가 판단과 401/403 분기

`AuthorizationFilter`는 `authorizeHttpRequests()`에 등록된 규칙(`.requestMatchers(...).hasRole(...)`, `.anyRequest().authenticated()` 등)을 현재 `SecurityContextHolder`의 `Authentication`과 대조한다.

- 규칙을 통과하면 그대로 다음(`DispatcherServlet`)으로.
- 통과 못 하면 `AccessDeniedException`을 던진다.
- 이 예외는 `ExceptionTranslationFilter`(10번)가 잡는다. 이때 현재 `Authentication`이 **익명 사용자**(8번이 채워 넣은 그 값)라면 "애초에 로그인부터 안 됐다"고 보고 `AuthenticationEntryPoint`를 호출해 **401**을, 익명이 아니라 **진짜 로그인된 사용자인데 권한/역할이 부족**하면 `AccessDeniedHandler`를 호출해 **403**을 응답한다.

즉 401/403의 경계는 "익명 사용자인가 아닌가"이지, "예외가 어디서 났는가"가 아니다.

---

## 6. `SecurityFilterChain` 설정 메서드별 작용 시점

| 설정 메서드 | 작용 시점/위치 | 주요 기능 및 예시 |
|---|---|---|
| `.authorizeHttpRequests()` | 11번(`AuthorizationFilter`) | URL/경로별 접근 권한. `.requestMatchers("/admin/**").hasRole("ADMIN")` |
| `.formLogin()` | 6번 필터 생성·활성화 | ID/PW 폼 로그인 사용 스위치. JWT면 `.disable()` |
| `.cors()` | 3번(`CorsFilter`) | 타 도메인(React 등) 간 리소스 공유 허용 |
| `.csrf()` | 4번(`CsrfFilter`) | 세션 기반이면 기본 유지, 순수 헤더-JWT면 `.disable()`. **"REST API니까 끈다"는 기준이 아니다 — 부록 A-2 참고** |
| `.sessionManagement()` | 2·9번 관련 | JWT 환경에서 `SessionCreationPolicy.STATELESS`로 세션 자체를 안 만들게 지정 |
| `.exceptionHandling()` | 10번(`ExceptionTranslationFilter`) | 401/403 커스텀 JSON 응답용 `AuthenticationEntryPoint`, `AccessDeniedHandler` 등록 |
| `.addFilterBefore()` | 특정 필터 전단 삽입 | 커스텀 JWT 필터를 `UsernamePasswordAuthenticationFilter` 앞에 배치 |
| `.addFilterAfter()` | 특정 필터 후단 삽입 | 로깅·Tracing·API 호출 통계용 커스텀 필터 배치 |

---

## 7. 보안 설정: cors, csrf

filterChain에서는 cors, csrf가 있는데 각각 무엇인지, 어떻게 설정하는지 알아보자.  

### csrf란?
Cross Site Request Forgery의 약자로, 사이트간 요청 위조를 뜻한다.  
사용자 자신의 의지와 무관하게 공격자가 의도한 행위를 특정 웹사이트에 요청하게 하는 공격이다.  
이가 성공하려면 다음과 같은 조건이 만족되어야 한다. 
1) target 서버가 CSRF 방어(토큰 검증, SameSite 등)를 갖추지 않은 상태에서,
   사용자가 그 서버에 이미 로그인(세션 쿠키 보유)되어 있어야 한다.  
2) target 서버가 인증을 쿠키 기반 세션으로 처리해야 한다
   (공격자가 그 값을 알 필요는 없음 — 브라우저가 요청 시 자동 첨부하기 때문)
3) 서버 공격을 위한 요청 방법에 대해 파악하고 있다.

이가 만족되었을 때 CSRF공격이 이루어지는 과정은 
1) sessionID가 사용자의 브라우저 쿠키에 저장된다.
2) 사용자가 악성 스크립트 페이지를 누르도록 유도한다. (이미지, 페이지 링크 등)
3) 웹 브라우저에 의해 쿠키에 저장된 sessionID와 함께 서버로 요청이 전달된다.
4) 서버는 쿠키에 담긴 sessionID를 통해 해당 요청이 인증된 사용자로부터 온 것으로 판단하고 처리한다.

### cors란?
Cross-Origin Resource Sharing의 약자로, 다른 출처의 자원의 공유 에 대한 정책을 뜻한다.  
프로토콜/도메인/포트 중 하나라도 다르면 다른 출처로 인식하는데, 예를들어  
https://example.com/ 이 있다고 할 때  
https://example.com/index.html는 same origin (Path만 다름),  
http://example.com/index.html   
https://cross-example.com/index.html  
https://example.com:1234  
나머지 3개는 전부 Cross Origin이다. (Scheme, Host, Port가 다름)  

### csrf, cors 공격 예시와 차이점
<img width="706" height="450" alt="image" src="https://github.com/user-attachments/assets/960c39cd-1bbe-4d8c-8ed8-0db9edcc0d6d" />


### CSRF 방어 방식

1. **Synchronizer Token Pattern (CSRF 토큰)**: 서버가 세션마다 예측 불가능한 토큰을 발급하고,
   상태 변경 요청(POST/PUT/DELETE)에는 그 토큰을 같이 보내도록 강제한다. 공격자는 target의 응답을
   읽을 수 없으니(SOP) 이 토큰값을 알 방법이 없어 위조 요청이 막힌다. Spring Security의 기본 CSRF 보호가 이 방식이다.

2. **Double Submit Cookie 패턴**: 토큰을 (JS가 읽을 수 있는) 쿠키로도 내려주고, 클라이언트가 그 값을
   직접 읽어 커스텀 헤더(`X-XSRF-TOKEN`)에 실어 재전송하게 한다. 서버는 쿠키값과 헤더값이 일치하는지만
   확인한다. SPA(React 등)에서 세션 방식 토큰 저장 없이도 CSRF를 막을 때 주로 쓴다.

3. **SameSite 쿠키 속성**: `Lax`/`Strict`로 설정하면 cross-site 요청 자체에 쿠키가 안 실려서
   보조 방어가 된다. 다만 `Lax`는 최상위 GET 네비게이션은 허용하므로, 상태 변경을 GET으로 처리하는
   서버라면 여전히 뚫린다 — 단독으로는 불충분하고 토큰 방식과 병행해야 한다.

4. **Referer/Origin 헤더 검증**: 요청의 출처 헤더가 자기 도메인인지 서버가 대조하는 보조 수단.
   헤더가 위조/누락될 수 있어 1차 방어로는 안 쓰고 부가 검증 정도로만 활용한다.

**Spring Security 설정**

```java
// 세션 기반 또는 쿠키에 JWT를 담는 경우 — CSRF 유지, SPA용 Double Submit Cookie로 구성
.csrf(csrf -> csrf
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        // XSRF-TOKEN 쿠키 발급. httpOnly=false로 둬야 클라이언트 JS가 읽어서 헤더에 재전송할 수 있음
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        // 기본 XorCsrfTokenRequestAttributeHandler는 토큰을 XOR로 난독화해서 내려주는데,
        // 이러면 클라이언트가 쿠키에서 읽은 원문 값과 서버가 기대하는 값이 달라 SPA 방식이 깨진다.
        // 그래서 난독화 없이 원문 그대로 비교하는 핸들러로 교체.
.addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
        // CsrfToken은 기본적으로 지연 로딩(실제로 누가 읽어야 값이 생성됨)이라,
        // 아무도 명시적으로 안 읽으면 쿠키 자체가 안 내려간다. 매 요청마다 강제로 로드시켜 쿠키 발급을 보장.

// 순수 헤더 기반 JWT (쿠키 사용 안 함)인 경우 — CSRF 벡터 자체가 없어 비활성화 가능
.csrf(csrf -> csrf.disable())
```

### CORS 방어(=올바른 설정) 방식

CORS는 "방어책을 추가"하는 개념이 아니라, **완화 범위를 정확히 좁혀서 설정하는 것 자체가 방어**다.

1. **화이트리스트 방식으로 origin을 명시**: 요청의 `Origin` 헤더를 검증 없이 그대로 반사(echo)하거나
   `*`를 쓰지 않고, 허용할 origin을 정확한 문자열로 나열한다.
2. **`Allow-Credentials`는 꼭 필요한 origin에만**: 쿠키/인증정보를 실어야 하는 요청이 아니면 켜지 않는다.
   (`*`와 `Allow-Credentials: true`는 스펙상 동시 사용이 브라우저에서 거부되므로, 반드시 화이트리스트와 짝을 맞춰야 한다.)
3. **허용 메서드/헤더도 최소화**: 실제로 쓰는 메서드(GET/POST 등)와 헤더만 열어서 불필요한 공격 표면을 줄인다.

**Spring Security 설정**

```java
// SecurityConfig.java — CorsConfigurationSource 빈을 직접 정의해 명시적으로 연결하는 방식
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of("https://app.example.com")); // 화이트리스트, * 금지
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
    configuration.setAllowCredentials(true); // 쿠키/인증정보를 cross-origin으로 주고받아야 할 때만
    configuration.setMaxAge(3600L); // preflight 캐시 시간

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}

// .cors() 설정에서 위 빈을 사용
.cors(cors -> cors.configurationSource(corsConfigurationSource()))
```

---

## 8. `formLogin`을 대체하는 인증 디자인 패턴

### 8-1. JWT 패턴

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // 1. 기본 폼 로그인 및 Basic 인증 비활성화
        .formLogin(f -> f.disable())
        .httpBasic(h -> h.disable())

        // 2. 헤더 전용 JWT라면 CSRF 비활성화 안전 (쿠키로 주고받는다면 유지 — 부록 A-2)
        .csrf(c -> c.disable())

        // 3. 세션을 사용하지 않고 Stateless로 설정
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // 4. UsernamePasswordAuthenticationFilter 자리에 커스텀 JWT 필터 배치
        .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)

        // 5. 인가 규칙 설정
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .anyRequest().authenticated()
        );
    return http.build();
}
```

동작 원리: 클라이언트 요청 헤더의 `Authorization: Bearer <Token>`을 커스텀 `JwtAuthenticationFilter`가 검증하고, 유효하면 `SecurityContextHolder`에 인증 완료 토큰을 직접 주입해 뒤쪽 `AuthorizationFilter`(11번)를 통과시킨다.

### 8-2. `formLogin`을 대체하는 다른 인증 패턴 3가지

- **OAuth2 / OIDC (소셜 로그인)**: Kakao·Google·Naver 등 외부 인가 서버에 인증을 위임하고, 인증 완료 후 사용자 정보를 바탕으로 자체 JWT/세션을 발급한다. `.oauth2Login()`으로 체인을 구성한다.
- **API Key / Mutual TLS (M2M 서비스 간 통신)**: 사용자 개입 없이 서버 대 서버(마이크로서비스) 통신 시 `X-API-KEY` 헤더나 클라이언트 인증서(mTLS)를 검증한다.
- **Passkey / WebAuthn (생체 인증·비대칭키)**: 비밀번호를 주고받지 않고 지문/FaceID/FIDO2 보안 키로 클라이언트가 비대칭키 서명한 값을 서버가 공개키로 검증한다.

---

## 부록 A. 상황별 특수 케이스

본문(0~8장)은 인증 방식과 무관하게 항상 성립하는 공통 골격이었다. 실제로는 "세션 기반이냐 JWT냐", "토큰을 어디에 담아 주고받느냐"에 따라 같은 필터가 하는 일이 크게 달라진다.

### A-1. `SecurityContextHolderFilter`가 실제로 하는 일 — 세션 기반 vs Stateless(JWT)

2장에서 "2번 필터가 이전 `SecurityContext`를 복원한다"고 했는데, 이때 "복원할 무언가가 있는지"는 `SecurityContextRepository` 구현체에 달려있다.

- **세션 기반**(기본값): `HttpSessionSecurityContextRepository`가 쓰인다. 로그인 성공 시 `SecurityContext`가 `HttpSession`에 저장되고, 다음 요청부터 `JSESSIONID` 쿠키로 세션을 찾아 그 안의 컨텍스트를 복원한다. 그래서 두 번째 요청부터는 `UsernamePasswordAuthenticationFilter`를 다시 타지 않아도 로그인 상태가 유지된다.
- **`.sessionManagement(STATELESS)`**: 세션 자체를 만들지도, 읽지도 않는 설정이라(`NullSecurityContextRepository` 계열) 서버는 애초에 "이전 요청의 인증 상태"를 아무 데도 보관하지 않는다. 그 결과 `SecurityContextHolderFilter`는 매 요청마다 사실상 "복원할 게 없어서" 아무 일도 못 한다 — 그래서 JWT 환경에서는 커스텀 JWT 필터(8-1)가 **매 요청마다** 토큰을 파싱해서 `SecurityContextHolder`에 새로 인증 정보를 채워 넣어야 한다. 세션 기반과 달리 "한 번 로그인하면 서버가 기억해준다"는 개념 자체가 없고, 매 요청이 토큰 하나로 독립적으로 인증되는 구조다.

### A-2. CSRF — "REST API냐 아니냐"가 아니라 "인증 정보가 쿠키로 자동 전송되는가"가 기준

CSRF 공격이 성립하려면 브라우저가 **내 의지와 무관하게 인증 정보를 자동으로 실어 보내야** 한다. 이 조건이 성립하는지는 REST API 여부가 아니라 토큰을 어디에 담느냐로 갈린다.

- **세션 기반(쿠키에 세션ID)**: 브라우저가 매 요청마다 쿠키를 자동으로 붙인다 → CSRF 노출됨 → `.csrf()` 유지 필수.
- **JWT를 `Authorization` 헤더로만 주고받음**: 브라우저가 자동으로 헤더를 채워주지 않는다(클라이언트 JS가 명시적으로 담아야 함) → CSRF 공격 자체가 성립 안 함 → `.csrf().disable()`이 안전.
- **JWT를 쿠키(특히 httpOnly)에 담아 주고받음**: REST API에 JWT를 쓰고 있어도, 쿠키는 브라우저가 요청마다 자동으로 붙인다 → **CSRF 노출이 세션 기반과 똑같이 남아있다**. "JWT 쓰니까 CSRF는 꺼도 된다"고 생각하면 안 되는 대표적인 케이스다. 이럴 땐 `CookieCsrfTokenRepository.withHttpOnlyFalse()`로 별도의 `XSRF-TOKEN` 쿠키(이건 JS가 읽을 수 있어야 하므로 httpOnly=false)를 발급하고, 클라이언트가 그 값을 읽어 `X-XSRF-TOKEN` 헤더로 재전송하도록 강제해서 CSRF를 그대로 방어한다.

### A-3. 비동기 응답(Async·SSE)에서 `SecurityContext`가 사라지는 문제

`WebAsyncManagerIntegrationFilter`(2장의 1번)는 컨트롤러가 `Callable`/`DeferredResult`처럼 별도 스레드에서 비동기로 처리를 이어갈 때, 원래 요청 스레드의 `SecurityContext`를 그 작업 스레드로 전파해준다. 여기까지는 대부분 자동으로 잘 동작한다.

문제는 `SseEmitter`처럼 **연결이 끝나는 시점**이다. SSE는 클라이언트가 연결을 끊거나 타임아웃되면, 서블릿 컨테이너가 그 요청을 마무리하려고 `DispatcherType.ASYNC`로 필터 체인을 **한 번 더** 태운다. 이 재진입 시점엔 최초 요청 때와 달리 `SecurityContext`가 비어있을 수 있어서, `authorizeHttpRequests()`의 `.anyRequest().authenticated()` 같은 규칙에 걸려 `AccessDeniedException`이 난다. 이미 응답은 끝난 뒤라 클라이언트는 아무 문제도 못 느끼지만, 서버 로그에는 연결이 끊길 때마다 스택트레이스가 쌓인다.

해결은 이 재진입(`ASYNC`, 그리고 예외 처리용 재진입인 `ERROR`)에 대해서는 인가 검사를 다시 요구하지 않는 것이다 — 최초 `REQUEST` 단계에서 이미 인증·인가를 통과했으므로, 재진입은 그냥 통과시킨다.

```java
.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
        .requestMatchers("/", "/login/**").permitAll()
        .anyRequest().authenticated())
```
