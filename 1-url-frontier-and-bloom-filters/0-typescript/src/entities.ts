import {
    Column,
    CreateDateColumn,
    Entity,
    Index,
    PrimaryGeneratedColumn,
} from "typeorm"

// frontier_event is an append-only audit trail: one row per enqueue / enqueue-dup / dequeue.
// A spike in enqueue-dup rows is the earliest signal of a redirect loop or a bloom overflow.
@Entity("frontier_event")
export class FrontierEventEntity {
    @PrimaryGeneratedColumn("uuid")
    id: string

    // action is indexed so dedup-rate analytics ("how many enqueue-dup in the last hour?") stay cheap.
    @Index()
    @Column({ type: "text" })
    action: string

    @Column({ type: "text" })
    url: string

    @Column({ type: "int" })
    priority: number

    @CreateDateColumn({ type: "timestamptz", name: "created_at" })
    createdAt: Date
}
