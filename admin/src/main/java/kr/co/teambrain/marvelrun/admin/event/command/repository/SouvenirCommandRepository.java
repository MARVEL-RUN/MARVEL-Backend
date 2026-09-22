package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Souvenir;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SouvenirCommandRepository extends JpaRepository<Souvenir, String> {
}