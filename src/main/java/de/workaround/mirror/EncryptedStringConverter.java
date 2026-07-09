package de.workaround.mirror;

import io.quarkus.arc.Arc;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter encrypting a string column at rest via {@link SecretCrypto}. The bean is resolved
 * lazily through ArC because Hibernate may instantiate converters outside CDI. Apply explicitly
 * with {@code @Convert} — never {@code autoApply}, encrypting a column must be a visible decision.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String>
{
	@Override
	public String convertToDatabaseColumn(String attribute)
	{
		return attribute == null ? null : crypto().encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData)
	{
		return dbData == null ? null : crypto().decrypt(dbData);
	}

	private static SecretCrypto crypto()
	{
		return Arc.container().instance(SecretCrypto.class).get();
	}

}
