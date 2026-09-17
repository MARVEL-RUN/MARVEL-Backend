package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@MappedSuperclass
public abstract class UserConsentBase<U extends UserBase> {
    @Id
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id", nullable = false)
    protected U user;

    @Column(name = "consent_to_terms_of_service", nullable = false)
    protected Boolean consentToTermsOfService = false;

    @Column(name = "consent_to_privacy_policy", nullable = false)
    protected Boolean consentToPrivacyPolicy = false;

    @Column(name = "consent_to_personal_info_collection_and_use", nullable = false)
    protected Boolean consentToPersonalInfoCollectionAndUse = false;

    @Column(name = "consent_to_marketing_and_advertising_SMS", nullable = false)
    protected Boolean consentToMarketingAndAdvertisingSMS = false;

    @Column(name = "consent_to_marketing_and_advertising_email", nullable = false)
    protected Boolean consentToMarketingAndAdvertisingEmail = false;

}