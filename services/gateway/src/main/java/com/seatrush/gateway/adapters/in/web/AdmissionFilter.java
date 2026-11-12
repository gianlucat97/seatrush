package com.seatrush.gateway.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.gateway.domain.PurchaseCommand;
import com.seatrush.security.tokens.AdmissionToken;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenException;
import com.seatrush.security.tokens.UserToken;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The door of the purchase area, checked on the three commands that go through it (README sections
 * 6.1 and 13).
 *
 * <p>This is what makes the waiting room mean anything. Without it, the queue would be a screen
 * people watch while holding seats they took directly, and {@code max-active-users} would bound
 * nothing. The token it demands is minted by queue-service from a live slot (ADR-0016), signed with
 * a secret only these two services hold, and checked here so that no service behind the gateway has
 * to know a queue exists.
 *
 * <p>Three checks, in the order of how much they rule out. The signature and the expiry say it is
 * an admission this deployment issued and that it has not lapsed; the subject says it is the
 * caller's own and not one passed to them; the event, where the path names one, says it is for this
 * sale. The third is the reason an admission carries an event at all: without it, queueing for a
 * quiet event would be a way into every on-sale in the system.
 *
 * <p>Two of the three commands name a hold or an order instead of an event, and for those the
 * binding is checked where the event is known: inventory refuses a hold that is not the caller's,
 * order-service refuses an order that is not theirs. The gateway does not read that state in order
 * to repeat a check its owner already makes.
 *
 * <p>Refused with {@code 403 ADMISSION_REQUIRED} rather than 401, because the caller is
 * authenticated and known. What they lack is a turn.
 */
@Component
public class AdmissionFilter implements WebFilter, Ordered {

  /** Where a client presents the admission it was given on the waiting room stream. */
  public static final String ADMISSION_HEADER = "X-Admission-Token";

  private static final Logger log = LoggerFactory.getLogger(AdmissionFilter.class);

  private final AdmissionTokenSigner admissions;
  private final ProblemResponses problems;

  public AdmissionFilter(AdmissionTokenSigner admissions, ProblemResponses problems) {
    this.admissions = admissions;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    String method = exchange.getRequest().getMethod().name();
    if (!PurchaseCommand.requiresAdmission(method, path)) {
      return chain.filter(withoutTheAdmission(exchange));
    }

    String presented = exchange.getRequest().getHeaders().getFirst(ADMISSION_HEADER);
    if (presented == null || presented.isBlank()) {
      return refuse(exchange, "This command needs the admission token the waiting room gave you");
    }

    AdmissionToken admission;
    try {
      admission = admissions.verify(presented);
    } catch (TokenException invalid) {
      // Ordinary rather than alarming: an admission lasts minutes, so the common case here is a
      // buyer who took too long. The reason stays in the log for the same reason as in the user
      // filter, which is that only an attacker learns anything from being told which check failed.
      log.debug("a purchase command on {} carried an admission that is not valid", path, invalid);
      return refuse(exchange, "Your admission is not valid or has expired");
    }

    UserToken caller = exchange.getAttribute(UserAuthenticationFilter.USER_TOKEN_ATTRIBUTE);
    if (caller == null) {
      // Unreachable while the user filter runs first, and worth failing loudly rather than
      // skipping: a purchase command whose caller is unknown must never be forwarded.
      throw new IllegalStateException(
          "a purchase command reached the admission filter with no authenticated caller");
    }
    if (!admission.userId().equals(caller.userId())) {
      log.warn(
          "user {} presented an admission belonging to {}", caller.userId(), admission.userId());
      return refuse(exchange, "This admission was issued to somebody else");
    }

    Optional<UUID> event = PurchaseCommand.eventOf(path);
    if (event.isPresent() && !admission.admits(event.get())) {
      return refuse(exchange, "This admission is for another event");
    }

    return chain.filter(withoutTheAdmission(exchange));
  }

  /** After the user filter, which is where the caller this one compares against comes from. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }

  private Mono<Void> refuse(ServerWebExchange exchange, String detail) {
    return problems.write(exchange, ErrorCode.ADMISSION_REQUIRED, detail);
  }

  /**
   * The request as the services behind this one see it: without the admission.
   *
   * <p>Nothing downstream reads it, and a bearer token that travels further than the thing that
   * checks it is a bearer token in one more log file.
   */
  private static ServerWebExchange withoutTheAdmission(ServerWebExchange exchange) {
    return exchange
        .mutate()
        .request(request -> request.headers(headers -> headers.remove(ADMISSION_HEADER)))
        .build();
  }
}
