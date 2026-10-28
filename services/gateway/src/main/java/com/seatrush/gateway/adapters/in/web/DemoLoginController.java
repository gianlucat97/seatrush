package com.seatrush.gateway.adapters.in.web;

import com.seatrush.gateway.application.DemoLogins;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint the gateway answers itself (README section 12.1).
 *
 * <p>Everything else here is routed, and this cannot be: a login is where a token comes from, so
 * there is no service behind the gateway that could be asked for one without that service holding
 * the signing secret too.
 *
 * <p>Present in the {@code local} and {@code bench} profiles only, which is what README section 22
 * means by disabling the demo login on Kubernetes. The profile is the switch rather than a flag,
 * because a flag is a value somebody can set wrongly in production and still start the service.
 */
@RestController
@RequestMapping("/api/auth")
@Profile({"local", "bench"})
public class DemoLoginController {

  private static final Logger log = LoggerFactory.getLogger(DemoLoginController.class);

  private final DemoLogins logins;

  public DemoLoginController(DemoLogins logins) {
    this.logins = logins;
  }

  /**
   * Becomes somebody for the lifetime of a token.
   *
   * <p>200 and not 201: nothing was created. The same name logged in twice is the same user with a
   * second token, which is the behaviour a page that was reloaded needs.
   */
  @PostMapping("/demo-login")
  public DemoLoginResponse login(@Valid @RequestBody DemoLoginRequest request) {
    DemoLoginResponse response =
        DemoLoginResponse.of(logins.login(request.username(), request.admin()));
    log.info("demo login as {} ({})", response.username(), response.userId());
    return response;
  }
}
