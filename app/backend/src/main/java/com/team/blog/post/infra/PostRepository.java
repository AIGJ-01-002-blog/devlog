package com.team.blog.post.infra;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.team.blog.post.domain.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** 작성자 본인의 삭제되지 않은 글을 행 잠금으로 읽는다 (docs/05 §7 ③, J-4). 남의 글이면 비어 있다 → 404. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id and p.authorId = :authorId and p.deletedAt is null")
    Optional<Post> findOwnForUpdate(long id, long authorId);

    @Query("select p from Post p where p.id = :id and p.authorId = :authorId and p.deletedAt is null")
    Optional<Post> findOwn(long id, long authorId);

    /** 아직 아무것도 쓰지 않은 임시글 (최근 것부터). [새 글]을 다시 눌렀을 때 새로 만들지 않고 이어 쓴다. */
    @Query(value = "SELECT p.id FROM post p WHERE p.author_id = :authorId AND p.deleted_at IS NULL AND "
            + PostSql.BLANK_DRAFT + " ORDER BY p.id DESC LIMIT 5", nativeQuery = true)
    java.util.List<Long> findEmptyDraftIds(long authorId);
}
