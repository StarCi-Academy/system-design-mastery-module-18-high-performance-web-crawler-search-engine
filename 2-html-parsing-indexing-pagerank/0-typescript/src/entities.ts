import {
    Column,
    Entity,
    PrimaryColumn,
    PrimaryGeneratedColumn,
    Unique,
} from "typeorm"

// page_link stores one directed edge (fromUrl -> toUrl) of the web graph.
@Entity("page_link")
@Unique(["fromUrl", "toUrl"]) // an edge is a set member: the same (from, to) pair appears at most once
export class PageLink {
    @PrimaryGeneratedColumn("uuid")
    id: string

    @Column({ type: "text", name: "from_url" })
    fromUrl: string

    @Column({ type: "text", name: "to_url" })
    toUrl: string
}

// page_rank stores the computed authority score per URL after a PageRank batch run.
@Entity("page_rank")
export class PageRank {
    // The URL is the natural primary key: one rank row per page.
    @PrimaryColumn({ type: "text" })
    url: string

    @Column({ type: "double precision" })
    rank: number

    @Column({ type: "int" })
    iterations: number

    @Column({ type: "timestamptz", name: "updated_at" })
    updatedAt: Date
}
