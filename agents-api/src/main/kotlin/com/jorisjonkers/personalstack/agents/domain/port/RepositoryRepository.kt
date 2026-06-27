package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId

/**
 * Persistence port for [Repository]. The junction table that
 * carries the M:N membership against Project is owned by
 * [ProjectRepositoryRepository]; this port stays focused on the
 * single-table CRUD so the deploy-key flow can use it without
 * pulling in junction concerns.
 */
interface RepositoryRepository {
    fun save(repository: Repository): Repository

    fun findById(id: RepositoryId): Repository?

    fun findByName(name: String): Repository?

    /**
     * Looks up a repository by its clone URL. A repository's identity is its
     * URL, not its short name: the same name may appear more than once (e.g.
     * `.github` under two different GitHub orgs during a migration), so name
     * is no longer unique. Used to dedup on create.
     */
    fun findByRepoUrl(repoUrl: String): Repository?

    fun findAll(): List<Repository>

    fun findAllByProjectId(projectId: ProjectId): List<Repository>

    fun delete(id: RepositoryId)
}
