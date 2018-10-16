package no.fint.consumer.models.skole;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;

import no.fint.cache.CacheService;
import no.fint.consumer.config.Constants;
import no.fint.consumer.config.ConsumerProps;
import no.fint.consumer.event.ConsumerEventUtil;
import no.fint.event.model.Event;
import no.fint.event.model.ResponseStatus;
import no.fint.model.felles.kompleksedatatyper.Identifikator;
import no.fint.relations.FintResourceCompatibility;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;

import no.fint.model.utdanning.utdanningsprogram.Skole;
import no.fint.model.resource.utdanning.utdanningsprogram.SkoleResource;
import no.fint.model.utdanning.utdanningsprogram.UtdanningsprogramActions;

@Slf4j
@Service
public class SkoleCacheService extends CacheService<SkoleResource> {

    public static final String MODEL = Skole.class.getSimpleName().toLowerCase();

    @Value("${fint.consumer.compatibility.fintresource:true}")
    private boolean checkFintResourceCompatibility;

    @Autowired
    private FintResourceCompatibility fintResourceCompatibility;

    @Autowired
    private ConsumerEventUtil consumerEventUtil;

    @Autowired
    private ConsumerProps props;

    @Autowired
    private SkoleLinker linker;

    private JavaType javaType;

    private ObjectMapper objectMapper;

    public SkoleCacheService() {
        super(MODEL, UtdanningsprogramActions.GET_ALL_SKOLE, UtdanningsprogramActions.UPDATE_SKOLE);
        objectMapper = new ObjectMapper();
        javaType = objectMapper.getTypeFactory().constructCollectionType(List.class, SkoleResource.class);
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @PostConstruct
    public void init() {
        props.getAssets().forEach(this::createCache);
    }

    @Scheduled(initialDelayString = Constants.CACHE_INITIALDELAY_SKOLE, fixedRateString = Constants.CACHE_FIXEDRATE_SKOLE)
    public void populateCacheAll() {
        props.getAssets().forEach(this::populateCache);
    }

    public void rebuildCache(String orgId) {
		flush(orgId);
		populateCache(orgId);
	}

    private void populateCache(String orgId) {
		log.info("Populating Skole cache for {}", orgId);
        Event event = new Event(orgId, Constants.COMPONENT, UtdanningsprogramActions.GET_ALL_SKOLE, Constants.CACHE_SERVICE);
        consumerEventUtil.send(event);
    }


    public Optional<SkoleResource> getSkoleBySkolenummer(String orgId, String skolenummer) {
        return getOne(orgId, (resource) -> Optional
                .ofNullable(resource)
                .map(SkoleResource::getSkolenummer)
                .map(Identifikator::getIdentifikatorverdi)
                .map(_id -> _id.equals(skolenummer))
                .orElse(false));
    }

    public Optional<SkoleResource> getSkoleBySystemId(String orgId, String systemId) {
        return getOne(orgId, (resource) -> Optional
                .ofNullable(resource)
                .map(SkoleResource::getSystemId)
                .map(Identifikator::getIdentifikatorverdi)
                .map(_id -> _id.equals(systemId))
                .orElse(false));
    }

    public Optional<SkoleResource> getSkoleByOrganisasjonsnummer(String orgId, String organisasjonsnummer) {
        return getOne(orgId, (resource) -> Optional
                .ofNullable(resource)
                .map(SkoleResource::getOrganisasjonsnummer)
                .map(Identifikator::getIdentifikatorverdi)
                .map(_id -> _id.equals(organisasjonsnummer))
                .orElse(false));
    }


	@Override
    public void onAction(Event event) {
        List<SkoleResource> data;
        if (checkFintResourceCompatibility && fintResourceCompatibility.isFintResourceData(event.getData())) {
            log.info("Compatibility: Converting FintResource<SkoleResource> to SkoleResource ...");
            data = fintResourceCompatibility.convertResourceData(event.getData(), SkoleResource.class);
        } else {
            data = objectMapper.convertValue(event.getData(), javaType);
        }
        data.forEach(linker::mapLinks);
        if (UtdanningsprogramActions.valueOf(event.getAction()) == UtdanningsprogramActions.UPDATE_SKOLE) {
            if (event.getResponseStatus() == ResponseStatus.ACCEPTED || event.getResponseStatus() == ResponseStatus.CONFLICT) {
                add(event.getOrgId(), data);
                log.info("Added {} elements to cache for {}", data.size(), event.getOrgId());
            } else {
                log.debug("Ignoring payload for {} with response status {}", event.getOrgId(), event.getResponseStatus());
            }
        } else {
            update(event.getOrgId(), data);
            log.info("Updated cache for {} with {} elements", event.getOrgId(), data.size());
        }
    }
}
