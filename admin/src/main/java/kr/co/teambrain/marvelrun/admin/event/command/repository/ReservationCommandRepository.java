package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ReservationCommandRepository.java
public interface ReservationCommandRepository extends JpaRepository<Reservation, String> {
    Optional<Reservation> findByRegistration_Id(String registrationId);
}