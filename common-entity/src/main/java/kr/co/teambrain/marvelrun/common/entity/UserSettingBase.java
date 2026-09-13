package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor // abstract Class지만 SuperBuilder를 사용해야하므로 별도로 작성. Builder 어노테이션이 자체적으로 생성자를 만들어버려 기본생성자 생성이 안되기때문
@MappedSuperclass
public abstract class UserSettingBase<U extends UserBase>{
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id", nullable = false)
    protected U user;

    @Column(name = "push_alarm_toggle", nullable = false)
    protected Boolean pushAlarmToggle = true;



}
