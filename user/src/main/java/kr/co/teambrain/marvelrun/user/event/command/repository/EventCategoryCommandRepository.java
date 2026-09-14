package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventCategoryCommandRepository
        extends JpaRepository<EventCategory, String> {
}