package org.appfuse.webapp.action;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
import org.appfuse.Constants;
import org.appfuse.model.Role;
import org.appfuse.model.User;
import org.appfuse.service.RoleManager;
import org.appfuse.service.UserManager;
import org.appfuse.service.UserExistsException;
import org.appfuse.util.StringUtil;
import org.appfuse.webapp.util.RequestUtil;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Implementation of <strong>SimpleFormController</strong> that interacts with
 * the {@link UserManager} to retrieve/persist values to the database.
 *
 * <p><a href="UserFormController.java.html"><i>View Source</i></a></p>
 *
 * @author <a href="mailto:matt@raibledesigns.com">Matt Raible</a>
 */
public class UserFormController extends BaseFormController {
    private RoleManager roleManager;

    /**
     * @param roleManager The roleManager to set.
     */
    public void setRoleManager(RoleManager roleManager) {
        this.roleManager = roleManager;
    }

    public ModelAndView processFormSubmission(HttpServletRequest request,
                                              HttpServletResponse response,
                                              Object command,
                                              BindException errors)
    throws Exception {
        if (request.getParameter("cancel") != null) {
            if (!StringUtils.equals(request.getParameter("from"), "list")) {
                return new ModelAndView(new RedirectView("mainMenu.html"));
            } else {
                return new ModelAndView(new RedirectView("users.html"));
            }
        }

        return super.processFormSubmission(request, response, command, errors);
    }

    public ModelAndView onSubmit(HttpServletRequest request,
                                 HttpServletResponse response, Object command,
                                 BindException errors)
    throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("entering 'onSubmit' method...");
        }

        User user = (User) command;
        Locale locale = request.getLocale();

        if (request.getParameter("delete") != null) {
            mgr.removeUser(user.getUsername());
            saveMessage(request, getText("user.deleted", user.getFullName(), locale));

            return new ModelAndView(new RedirectView("users.html"));
        } else {
            if ("true".equals(request.getParameter("encryptPass"))) {
                String algorithm =
                    (String) getConfiguration().get(Constants.ENC_ALGORITHM);

                if (algorithm == null) { // should only happen for test case

                    if (log.isDebugEnabled()) {
                        log.debug("assuming testcase, setting algorithm to 'SHA'");
                    }

                    algorithm = "SHA";
                }

                user.setPassword(StringUtil.encodePassword(user.getPassword(),
                                                           algorithm));
            }

            String[] userRoles = request.getParameterValues("userRoles");

            if (userRoles != null) {
                // for some reason, Spring seems to hang on to the roles in
                // the User object, even though isSessionForm() == false
                user.getRoles().clear();
                for (int i = 0; i < userRoles.length; i++) {
                    String roleName = userRoles[i];
                    user.addRole(roleManager.getRole(roleName));
                }
            }

            try {
                mgr.saveUser(user);
            } catch (UserExistsException e) {
                log.warn(e.getMessage());

                errors.rejectValue("username", "errors.existing.user",
                                   new Object[] {
                                       user.getUsername(), user.getEmail()
                                   }, "duplicate user");

                // redisplay the unencrypted passwords
                user.setPassword(user.getConfirmPassword());

                return showForm(request, response, errors);
            }

            if (!StringUtils.equals(request.getParameter("from"), "list")) {
                HttpSession session = request.getSession();
                session.setAttribute(Constants.USER_KEY, user);

                // update the user's remember me cookie if they didn't login
                // with a cookie
                if ((RequestUtil.getCookie(request, Constants.LOGIN_COOKIE) != null) &&
                        (session.getAttribute("cookieLogin") == null)) {
                    // delete all user cookies and add a new one
                    mgr.removeLoginCookies(user.getUsername());

                    String autoLogin =
                        mgr.createLoginCookie(user.getUsername());
                    RequestUtil.setCookie(response, Constants.LOGIN_COOKIE,
                                          autoLogin, request.getContextPath());
                }

                saveMessage(request, getText("user.saved", user.getFullName(), locale));

                // return to main Menu
                return new ModelAndView(new RedirectView("mainMenu.html"));
            } else {
                if (StringUtils.isBlank(request.getParameter("version"))) {
                    saveMessage(request,
                                getText("user.added", user.getFullName(), locale));

                    // Send an account information e-mail
                    message.setSubject(getText("signup.email.subject", locale));
                    sendUserMessage(user,
                                    getText("newuser.email.message",
                                            user.getFullName(), locale),
                                    RequestUtil.getAppURL(request));

                    return showNewForm(request, response);
                } else {
                    saveMessage(request,
                                getText("user.updated.byAdmin",
                                        user.getFullName(), locale));
                }
            }
        }

        return showForm(request, response, errors);
    }

    protected ModelAndView showForm(HttpServletRequest request,
                                    HttpServletResponse response,
                                    BindException errors)
    throws Exception {
        if (request.getRequestURI().indexOf("editProfile") > -1) {
            // if URL is "editProfile" - make sure it's the current user
            // reject if username passed in or "list" parameter passed in
            // someone that is trying this probably knows the AppFuse code
            // but it's a legitimate bug, so I'll fix it. ;-)
            if ((request.getParameter("username") != null) ||
                    (request.getParameter("from") != null)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                log.warn("User '" + request.getRemoteUser() +
                         "' is trying to edit user '" +
                         request.getParameter("username") + "'");

                return null;
            }
        }

        // prevent ordinary users from calling a GET on editUser.html
        // unless a bind error exists.
        if ((request.getRequestURI().indexOf("editUser") > -1) &&
                (!request.isUserInRole(Constants.ADMIN_ROLE) &&
                (errors.getErrorCount() == 0) && // be nice to server-side validation for editProfile
                (request.getRemoteUser() != null))) { // be nice to unit tests
            response.sendError(HttpServletResponse.SC_FORBIDDEN);

            return null;
        }

        return super.showForm(request, response, errors);
    }

    protected Object formBackingObject(HttpServletRequest request)
    throws Exception {
        String username = request.getParameter("username");

        if (request.getSession().getAttribute("cookieLogin") != null) {
            saveMessage(request, getText("userProfile.cookieLogin", request.getLocale()));
        }

        User user = null;

        if (request.getRequestURI().indexOf("editProfile") > -1) {
            user = mgr.getUser(getUser(request).getUsername());
        } else if (!StringUtils.isBlank(username) &&
                       !"".equals(request.getParameter("version"))) {
            user = mgr.getUser(username);
        } else {
            user = new User();
            user.addRole(new Role(Constants.USER_ROLE));
        }

        user.setConfirmPassword(user.getPassword());

        return user;
    }

    protected void onBind(HttpServletRequest request, Object command)
    throws Exception {
        // if the user is being deleted, turn off validation
        if (request.getParameter("delete") != null) {
            super.setValidateOnBinding(false);
        } else {
            super.setValidateOnBinding(true);
        }
    }
}
