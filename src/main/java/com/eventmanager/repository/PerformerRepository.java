package com.eventmanager.repository;

import com.eventmanager.model.Performer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PerformerRepository extends JpaRepository<Performer, Long> {

    @Query("SELECT DISTINCT p FROM Performer p LEFT JOIN FETCH p.videos")
    List<Performer> findAllWithVideos();

    @Query("SELECT p FROM Performer p LEFT JOIN FETCH p.videos WHERE p.id = :id")
    Optional<Performer> findByIdWithVideos(@Param("id") Long id);

    @Query("SELECT DISTINCT p FROM Performer p LEFT JOIN FETCH p.videos WHERE LOWER(p.genre) = LOWER(:genre)")
    List<Performer> findByGenreIgnoreCaseWithVideos(@Param("genre") String genre);

    @Query("SELECT DISTINCT p FROM Performer p LEFT JOIN FETCH p.videos WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Performer> findByNameContainingIgnoreCaseWithVideos(@Param("name") String name);
}
