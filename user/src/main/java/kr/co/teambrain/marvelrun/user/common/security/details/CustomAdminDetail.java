package kr.co.teambrain.marvelrun.user.common.security.details;


import kr.co.teambrain.marvelrun.user.common.entities.Admin;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class CustomAdminDetail
        implements UserDetails {

    private final String adminId;

    private final String adminName;

    private final String roleName;


    public CustomAdminDetail(
            Admin admin
    ) {

        this.adminId =
                admin.getId();

        this.adminName =
                admin.getName();

        this.roleName =
                admin.getRole()
                        .getName();
    }


    public CustomAdminDetail(
            String adminId,
            String adminName,
            String roleName
    ) {

        this.adminId = adminId;
        this.adminName = adminName;
        this.roleName = roleName;
    }


    @Override
    public Collection<? extends GrantedAuthority>
    getAuthorities() {

        return List.of(
                new SimpleGrantedAuthority(
                        "ROLE_" + roleName
                )
        );
    }


    @Override
    public String getPassword() {

        return "";
    }


    @Override
    public String getUsername() {

        return adminId;
    }


    public String getName() {

        return adminName;
    }


    public String getRoleName() {

        return roleName;
    }
}