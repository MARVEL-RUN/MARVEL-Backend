package kr.co.teambrain.marvelrun.admin.event.command.repository;


import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventCategoryCommandRepository
        extends JpaRepository<EventCategory, String> {
}