package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationItemCommandRepository extends JpaRepository<ReservationItem, String> {
    List<ReservationItem> findAllByReservation_Id(String reservationId);
}