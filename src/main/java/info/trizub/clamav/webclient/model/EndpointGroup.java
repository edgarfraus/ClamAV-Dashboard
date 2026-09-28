package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;

@Entity
@Table(name = "endpoint_groups")
public class EndpointGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    // Desired realtime mode for this group's Linux endpoints with on-access:
    // false = detection (report only), true = prevention (block access to the
    // infected file). The agent applies it locally on its next poll, so it is
    // not immediate. Explicit columnDefinition so the column gets a default
    // on rows that already exist too.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean onAccessPrevent = false;

    public EndpointGroup() {}

    public EndpointGroup(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isOnAccessPrevent() { return onAccessPrevent; }
    public void setOnAccessPrevent(boolean onAccessPrevent) { this.onAccessPrevent = onAccessPrevent; }
}
