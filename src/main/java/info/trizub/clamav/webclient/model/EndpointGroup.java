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

    // Modalita' realtime desiderata per gli endpoint Linux con on-access di
    // questo gruppo: false = detection (segnala soltanto), true = prevention
    // (blocca l'accesso al file infetto). L'agent la applica in locale al
    // prossimo poll, non e' immediata. Esplicito columnDefinition cosi' la
    // colonna nasce con un default anche sulle righe gia' esistenti.
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
